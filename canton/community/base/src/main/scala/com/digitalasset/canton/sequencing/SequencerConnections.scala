// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing

import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.{Id, Monad}
import com.daml.nonempty.{NonEmpty, NonEmptyUtil}
import com.digitalasset.canton.admin.domain.v30
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.version.{
  HasVersionedMessageCompanion,
  HasVersionedMessageCompanionCommon,
  HasVersionedMessageCompanionDbHelpers,
  HasVersionedWrapper,
  ProtoVersion,
  ProtocolVersion,
}
import com.digitalasset.canton.{ProtoDeserializationError, SequencerAlias}
import com.google.protobuf.ByteString

import java.net.URI

final case class SequencerConnections private (
    aliasToConnection: NonEmpty[Map[SequencerAlias, SequencerConnection]],
    sequencerTrustThreshold: PositiveInt,
    submissionRequestAmplification: PositiveInt,
) extends HasVersionedWrapper[SequencerConnections]
    with PrettyPrinting {
  require(
    aliasToConnection.sizeIs >= sequencerTrustThreshold.unwrap,
    s"sequencerTrustThreshold cannot be greater than number of sequencer connections. Found threshold of $sequencerTrustThreshold and ${aliasToConnection.size} sequencer connections",
  )

  aliasToConnection.foreach { case (alias, connection) =>
    require(
      alias == connection.sequencerAlias,
      "SequencerAlias in the Map must match SequencerConnection.sequencerAlias",
    )
  }

  def default: SequencerConnection = aliasToConnection.head1._2

  def connections: NonEmpty[Seq[SequencerConnection]] = aliasToConnection.map(_._2).toSeq

  def modify(
      sequencerAlias: SequencerAlias,
      m: SequencerConnection => SequencerConnection,
  ): SequencerConnections = modifyM[Id](sequencerAlias, m)

  private def modifyM[M[_]](
      sequencerAlias: SequencerAlias,
      m: SequencerConnection => M[SequencerConnection],
  )(implicit M: Monad[M]): M[SequencerConnections] =
    aliasToConnection
      .get(sequencerAlias)
      .map { connection =>
        M.map(m(connection)) { newSequencerConnection =>
          this.copy(
            aliasToConnection.updated(
              sequencerAlias,
              newSequencerConnection,
            )
          )
        }
      }
      .getOrElse(M.pure(this))

  def addEndpoints(
      sequencerAlias: SequencerAlias,
      connection: URI,
      additionalConnections: URI*
  ): Either[String, SequencerConnections] =
    (Seq(connection) ++ additionalConnections).foldLeftM(this) { case (acc, elem) =>
      acc.modifyM(sequencerAlias, c => c.addEndpoints(elem))
    }

  def addEndpoints(
      sequencerAlias: SequencerAlias,
      connection: SequencerConnection,
      additionalConnections: SequencerConnection*
  ): Either[String, SequencerConnections] =
    (Seq(connection) ++ additionalConnections).foldLeftM(this) { case (acc, elem) =>
      acc.modifyM(sequencerAlias, c => c.addEndpoints(elem))
    }

  def withCertificates(
      sequencerAlias: SequencerAlias,
      certificates: ByteString,
  ): SequencerConnections =
    modify(sequencerAlias, _.withCertificates(certificates))

  override def pretty: Pretty[SequencerConnections] =
    prettyOfParam(_.aliasToConnection.forgetNE)

  def toProtoV30: v30.SequencerConnections =
    new v30.SequencerConnections(
      connections.map(_.toProtoV30),
      sequencerTrustThreshold.unwrap,
      submissionRequestAmplification.unwrap,
    )

  @transient override protected lazy val companionObj
      : HasVersionedMessageCompanionCommon[SequencerConnections] =
    SequencerConnections
}

object SequencerConnections
    extends HasVersionedMessageCompanion[SequencerConnections]
    with HasVersionedMessageCompanionDbHelpers[SequencerConnections] {

  def single(connection: SequencerConnection): SequencerConnections =
    new SequencerConnections(
      aliasToConnection = NonEmpty.mk(Seq, (connection.sequencerAlias, connection)).toMap,
      sequencerTrustThreshold = PositiveInt.one,
      submissionRequestAmplification = PositiveInt.one,
    )

  def many(
      connections: NonEmpty[Seq[SequencerConnection]],
      sequencerTrustThreshold: PositiveInt,
      submissionRequestAmplification: PositiveInt,
  ): Either[String, SequencerConnections] =
    if (connections.sizeIs == 1) {
      Right(SequencerConnections.single(connections.head1))
    } else {
      val repeatedAliases = connections.groupBy(_.sequencerAlias).filter { case (_, connections) =>
        connections.lengthCompare(1) > 0
      }
      for {
        _ <- Either.cond(
          repeatedAliases.isEmpty,
          (),
          s"Repeated sequencer aliases found: $repeatedAliases",
        )
        sequencerConnections <- Either
          .catchOnly[IllegalArgumentException](
            new SequencerConnections(
              connections.map(conn => (conn.sequencerAlias, conn)).toMap,
              sequencerTrustThreshold,
              submissionRequestAmplification,
            )
          )
          .leftMap(_.getMessage)
      } yield sequencerConnections
    }

  def tryMany(
      connections: Seq[SequencerConnection],
      sequencerTrustThreshold: PositiveInt,
      submissionRequestAmplification: PositiveInt,
  ): SequencerConnections =
    many(
      NonEmptyUtil.fromUnsafe(connections),
      sequencerTrustThreshold,
      submissionRequestAmplification,
    ).valueOr(err => throw new IllegalArgumentException(err))

  def fromProtoV30(
      sequencerConnectionsProto: v30.SequencerConnections
  ): ParsingResult[SequencerConnections] = {
    val v30.SequencerConnections(
      sequencerConnectionsP,
      sequencerTrustThresholdP,
      submissionRequestAmplificationP,
    ) = sequencerConnectionsProto
    for {
      sequencerTrustThreshold <- ProtoConverter.parsePositiveInt(sequencerTrustThresholdP)
      submissionRequestAmplification <- ProtoConverter.parsePositiveInt(
        submissionRequestAmplificationP
      )
      sequencerConnectionsNes <- ProtoConverter.parseRequiredNonEmpty(
        SequencerConnection.fromProtoV30,
        "sequencer_connections",
        sequencerConnectionsP,
      )
      _ <- Either.cond(
        sequencerConnectionsNes.map(_.sequencerAlias).toSet.size == sequencerConnectionsNes.size,
        (),
        ProtoDeserializationError.ValueConversionError(
          "sequencer_connections",
          "Every sequencer connection must have a unique sequencer alias",
        ),
      )
      sequencerConnections <- many(
        sequencerConnectionsNes,
        sequencerTrustThreshold,
        submissionRequestAmplification,
      ).leftMap(ProtoDeserializationError.InvariantViolation(_))
    } yield sequencerConnections
  }

  override def name: String = "sequencer connections"

  val supportedProtoVersions: SupportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> ProtoCodec(
      ProtocolVersion.v30,
      supportedProtoVersion(v30.SequencerConnections)(fromProtoV30),
      _.toProtoV30.toByteString,
    )
  )
}
