// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.service

import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.common.domain.grpc.SequencerInfoLoader
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.domain.admin.v30
import com.digitalasset.canton.domain.admin.v30.SequencerConnectionServiceGrpc.SequencerConnectionService
import com.digitalasset.canton.lifecycle.{
  CloseContext,
  FlagCloseable,
  FutureUnlessShutdown,
  PromiseUnlessShutdown,
}
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.networking.grpc.CantonMutableHandlerRegistry
import com.digitalasset.canton.sequencing.client.SequencerClient.SequencerTransports
import com.digitalasset.canton.sequencing.client.{
  RequestSigner,
  RichSequencerClient,
  SequencerClientTransportFactory,
}
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.RetryUtil.NoExnRetryable
import com.digitalasset.canton.util.{EitherTUtil, retry}
import io.grpc.{Status, StatusException}
import monocle.Lens
import org.apache.pekko.stream.Materializer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class GrpcSequencerConnectionService(
    fetchConnection: () => EitherT[Future, String, Option[
      SequencerConnections
    ]],
    setConnection: SequencerConnections => EitherT[
      Future,
      String,
      Unit,
    ],
)(implicit ec: ExecutionContext)
    extends v30.SequencerConnectionServiceGrpc.SequencerConnectionService {
  override def getConnection(request: v30.GetConnectionRequest): Future[v30.GetConnectionResponse] =
    EitherTUtil.toFuture(
      fetchConnection()
        .leftMap(error => Status.FAILED_PRECONDITION.withDescription(error).asException())
        .map {
          case Some(sequencerConnections) =>
            v30.GetConnectionResponse(Some(sequencerConnections.toProtoV30))

          case None => v30.GetConnectionResponse(None)
        }
    )

  override def setConnection(request: v30.SetConnectionRequest): Future[v30.SetConnectionResponse] =
    EitherTUtil.toFuture(for {
      existing <- getConnection
      requestedReplacement <- parseConnection(request)
      _ <- validateReplacement(existing, requestedReplacement)
      _ <- setConnection(requestedReplacement)
        .leftMap(error => Status.FAILED_PRECONDITION.withDescription(error).asException())
    } yield v30.SetConnectionResponse())

  private def getConnection: EitherT[Future, StatusException, SequencerConnections] =
    fetchConnection()
      .leftMap(error => Status.INTERNAL.withDescription(error).asException())
      .flatMap[StatusException, SequencerConnections] {
        case None =>
          EitherT.leftT(
            Status.FAILED_PRECONDITION
              .withDescription(
                "Initialize node before attempting to change sequencer connection"
              )
              .asException()
          )
        case Some(conn) =>
          EitherT.rightT(conn)
      }

  private def parseConnection(
      request: v30.SetConnectionRequest
  ): EitherT[Future, StatusException, SequencerConnections] = {
    val v30.SetConnectionRequest(sequencerConnectionsPO) = request

    ProtoConverter
      .required("sequencerConnections", sequencerConnectionsPO)
      .flatMap(SequencerConnections.fromProtoV30)
      .leftMap(err => Status.INVALID_ARGUMENT.withDescription(err.message).asException())
      .toEitherT[Future]
  }

  private def validateReplacement(
      existing: SequencerConnections,
      requestedReplacement: SequencerConnections,
  ): EitherT[Future, StatusException, Unit] =
    (existing.default, requestedReplacement.default) match {
      // TODO(i12076): How should we be checking connetions here? what should be validated?
      case (_: GrpcSequencerConnection, _: GrpcSequencerConnection) =>
        EitherT.rightT[Future, StatusException](())
      case _ =>
        EitherT.leftT[Future, Unit](
          Status.INVALID_ARGUMENT
            .withDescription(
              "requested replacement connection info is not of the same type as the existing"
            )
            .asException()
        )
    }
}

object GrpcSequencerConnectionService {

  trait UpdateSequencerClient {
    def set(client: RichSequencerClient): Unit
  }

  def setup[C](member: Member)(
      registry: CantonMutableHandlerRegistry,
      fetchConfig: () => EitherT[Future, String, Option[C]],
      saveConfig: C => EitherT[Future, String, Unit],
      sequencerConnectionLens: Lens[C, SequencerConnections],
      requestSigner: RequestSigner,
      transportFactory: SequencerClientTransportFactory,
      sequencerInfoLoader: SequencerInfoLoader,
      domainAlias: DomainAlias,
  )(implicit
      executionContext: ExecutionContextExecutor,
      executionServiceFactory: ExecutionSequencerFactory,
      materializer: Materializer,
      traceContext: TraceContext,
      errorLoggingContext: ErrorLoggingContext,
      closeContext: CloseContext,
  ): UpdateSequencerClient = {
    val clientO = new AtomicReference[Option[RichSequencerClient]](None)
    registry.addServiceU(
      SequencerConnectionService.bindService(
        new GrpcSequencerConnectionService(
          fetchConnection = () => fetchConfig().map(_.map(sequencerConnectionLens.get)),
          setConnection = newSequencerConnection =>
            for {
              currentConfig <- fetchConfig()
              newConfig <- currentConfig.fold(
                EitherT.leftT[Future, C](
                  "Can't update config when none has yet been set. Please initialize node."
                )
              )(config =>
                EitherT.rightT(sequencerConnectionLens.replace(newSequencerConnection)(config))
              )
              // validate connection before making transport (as making transport will hang if the connection
              // is invalid)
              _ <- transportFactory
                .validateTransport(
                  newSequencerConnection,
                  logWarning = false,
                )
                .onShutdown(Left("Aborting due to shutdown"))

              newEndpointsInfo <- sequencerInfoLoader
                .loadSequencerEndpoints(domainAlias, newSequencerConnection)
                .leftMap(_.cause)

              sequencerTransportsMap <- transportFactory
                .makeTransport(
                  newEndpointsInfo.sequencerConnections,
                  member,
                  requestSigner,
                )

              sequencerTransports <- EitherT.fromEither[Future](
                SequencerTransports.from(
                  sequencerTransportsMap,
                  newEndpointsInfo.expectedSequencers,
                  newEndpointsInfo.sequencerConnections.sequencerTrustThreshold,
                  newEndpointsInfo.sequencerConnections.submissionRequestAmplification,
                )
              )

              // important to only save the config and change the transport after the `makeTransport` has run and done the handshake
              _ <- saveConfig(newConfig)
              _ <- EitherT.right(
                clientO
                  .get()
                  .fold {
                    // need to close here
                    sequencerTransportsMap.values.foreach(_.close())
                    Future.unit
                  }(_.changeTransport(sequencerTransports))
              )
            } yield (),
        ),
        executionContext,
      )
    )
    new UpdateSequencerClient {
      override def set(client: RichSequencerClient): Unit = clientO.set(Some(client))
    }
  }

  def waitUntilSequencerConnectionIsValid(
      factory: SequencerClientTransportFactory,
      flagCloseable: FlagCloseable,
      futureSupervisor: FutureSupervisor,
      loadConfig: => EitherT[Future, String, Option[
        SequencerConnections
      ]],
  )(implicit
      errorLoggingContext: ErrorLoggingContext,
      traceContext: TraceContext,
      executionContext: ExecutionContextExecutor,
  ): EitherT[Future, String, SequencerConnections] = {
    val promise =
      new PromiseUnlessShutdown[Either[String, SequencerConnection]](
        "wait-for-valid-connection",
        futureSupervisor,
      )
    flagCloseable.runOnShutdown_(promise)
    implicit val closeContext = CloseContext(flagCloseable)

    def tryNewConfig: EitherT[FutureUnlessShutdown, String, SequencerConnections] = {
      flagCloseable
        .performUnlessClosingEitherU("load config")(loadConfig)
        .flatMap {
          case Some(settings) =>
            factory
              .validateTransport(settings, logWarning = true)
              .map(_ => settings)
          case None => EitherT.leftT("No sequencer connection config")
        }
    }
    import scala.concurrent.duration.*
    EitherT(
      retry
        .Pause(
          errorLoggingContext.logger,
          flagCloseable,
          maxRetries = Int.MaxValue,
          delay = 50.millis,
          operationName = "wait-for-valid-sequencer-connection",
        )
        .unlessShutdown(
          tryNewConfig.value,
          NoExnRetryable,
        )
        .onShutdown(Left("Aborting due to shutdown"))
    )
  }

}
