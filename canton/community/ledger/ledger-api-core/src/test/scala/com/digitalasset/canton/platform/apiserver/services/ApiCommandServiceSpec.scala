// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.services

import com.daml.ledger.api.v1.commands.{Command, CreateCommand}
import com.daml.ledger.api.v1.value.{Identifier, Record, RecordField, Value}
import com.daml.ledger.api.v2.command_service.{
  SubmitAndWaitForTransactionResponse,
  SubmitAndWaitForTransactionTreeResponse,
  SubmitAndWaitForUpdateIdResponse,
  SubmitAndWaitRequest,
}
import com.daml.lf.data.Ref
import com.daml.tracing.NoOpTelemetry
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.ledger.api.MockMessages.*
import com.digitalasset.canton.ledger.api.services.CommandService
import com.digitalasset.canton.ledger.api.validation.{
  CommandsValidator,
  ValidateUpgradingPackageResolutions,
}
import com.digitalasset.canton.logging.LoggingContextWithTrace
import com.google.protobuf.empty.Empty
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ApiCommandServiceSpec
    extends AsyncWordSpec
    with MockitoSugar
    with Matchers
    with ArgumentMatchersSugar
    with BaseTest {

  import ApiCommandServiceSpec.*

  private val telemetry = NoOpTelemetry

  "ApiCommandService" should {
    "generate a submission ID if it's empty" in {
      val submissionCounter = new AtomicInteger
      val mockCommandService = createMockCommandService
      val grpcCommandService = new ApiCommandService(
        mockCommandService,
        commandsValidator = commandsValidator,
        currentLedgerTime = () => Instant.EPOCH,
        currentUtcTime = () => Instant.EPOCH,
        maxDeduplicationDuration = () => Some(Duration.ZERO),
        generateSubmissionId = () =>
          Ref.SubmissionId.assertFromString(
            s"$submissionIdPrefix${submissionCounter.incrementAndGet()}"
          ),
        telemetry = telemetry,
        loggerFactory = loggerFactory,
      )

      for {
        _ <- grpcCommandService.submitAndWait(aSubmitAndWaitRequestWithNoSubmissionId)
        _ <- grpcCommandService.submitAndWaitForTransaction(aSubmitAndWaitRequestWithNoSubmissionId)
        _ <- grpcCommandService.submitAndWaitForUpdateId(
          aSubmitAndWaitRequestWithNoSubmissionId
        )
        _ <- grpcCommandService.submitAndWaitForTransactionTree(
          aSubmitAndWaitRequestWithNoSubmissionId
        )
      } yield {
        def expectedSubmitAndWaitRequest(submissionIdSuffix: String): SubmitAndWaitRequest =
          aSubmitAndWaitRequestWithNoSubmissionId.copy(commands =
            aSubmitAndWaitRequestWithNoSubmissionId.commands
              .map(_.copy(submissionId = s"$submissionIdPrefix$submissionIdSuffix"))
          )
        val requestCaptorSubmitAndWait = ArgCaptor[SubmitAndWaitRequest]

        verify(mockCommandService).submitAndWait(requestCaptorSubmitAndWait.capture)(
          any[LoggingContextWithTrace]
        )
        requestCaptorSubmitAndWait.value shouldBe expectedSubmitAndWaitRequest("1")
        verify(mockCommandService).submitAndWaitForTransaction(requestCaptorSubmitAndWait.capture)(
          any[LoggingContextWithTrace]
        )
        requestCaptorSubmitAndWait.value shouldBe expectedSubmitAndWaitRequest("2")
        verify(mockCommandService).submitAndWaitForUpdateId(
          requestCaptorSubmitAndWait.capture
        )(any[LoggingContextWithTrace])
        requestCaptorSubmitAndWait.value shouldBe expectedSubmitAndWaitRequest("3")
        verify(mockCommandService).submitAndWaitForTransactionTree(
          requestCaptorSubmitAndWait.capture
        )(any[LoggingContextWithTrace])
        requestCaptorSubmitAndWait.value shouldBe expectedSubmitAndWaitRequest("4")
        succeed
      }
    }
    "accept submission with provided disclosed contracts" in {
      val mockCommandService = createMockCommandService

      val grpcCommandService = new ApiCommandService(
        mockCommandService,
        commandsValidator = commandsValidator,
        currentLedgerTime = () => Instant.EPOCH,
        currentUtcTime = () => Instant.EPOCH,
        maxDeduplicationDuration = () => Some(Duration.ZERO),
        generateSubmissionId = () => Ref.SubmissionId.assertFromString(s"submissionId"),
        telemetry = telemetry,
        loggerFactory = loggerFactory,
      )

      val submissionWithDisclosedContracts = aSubmitAndWaitRequestWithNoSubmissionId.update(
        _.commands.disclosedContracts.set(Seq(DisclosedContractCreator.disclosedContract))
      )

      for {
        _ <- grpcCommandService.submitAndWait(submissionWithDisclosedContracts)
        _ <- grpcCommandService.submitAndWaitForTransaction(submissionWithDisclosedContracts)
        _ <- grpcCommandService.submitAndWaitForUpdateId(submissionWithDisclosedContracts)
        _ <- grpcCommandService.submitAndWaitForTransactionTree(submissionWithDisclosedContracts)
      } yield {
        succeed
      }
    }

  }
}

object ApiCommandServiceSpec {
  private val aCommand = Command.of(
    Command.Command.Create(
      CreateCommand(
        Some(Identifier("package", moduleName = "module", entityName = "entity")),
        Some(
          Record(
            Some(Identifier("package", moduleName = "module", entityName = "entity")),
            Seq(RecordField("something", Some(Value(Value.Sum.Bool(true))))),
          )
        ),
      )
    )
  )

  private val aSubmitAndWaitRequestWithNoSubmissionId = submitAndWaitRequest.copy(
    commands = Some(commands.copy(commands = Seq(aCommand), submissionId = ""))
  )

  private val submissionIdPrefix = "submissionId-"

  private val commandsValidator = CommandsValidator(
    validateUpgradingPackageResolutions = ValidateUpgradingPackageResolutions.UpgradingDisabled,
    upgradingEnabled = false,
  )

  def createMockCommandService: CommandService & AutoCloseable = {
    import org.mockito.ArgumentMatchersSugar.*
    import org.mockito.MockitoSugar.*
    val mockCommandService = mock[CommandService & AutoCloseable]
    when(
      mockCommandService.submitAndWait(any[SubmitAndWaitRequest])(any[LoggingContextWithTrace])
    )
      .thenReturn(Future.successful(Empty.defaultInstance))
    when(
      mockCommandService.submitAndWaitForTransaction(any[SubmitAndWaitRequest])(
        any[LoggingContextWithTrace]
      )
    )
      .thenReturn(Future.successful(SubmitAndWaitForTransactionResponse.defaultInstance))
    when(
      mockCommandService.submitAndWaitForUpdateId(any[SubmitAndWaitRequest])(
        any[LoggingContextWithTrace]
      )
    )
      .thenReturn(Future.successful(SubmitAndWaitForUpdateIdResponse.defaultInstance))
    when(
      mockCommandService.submitAndWaitForTransactionTree(any[SubmitAndWaitRequest])(
        any[LoggingContextWithTrace]
      )
    )
      .thenReturn(Future.successful(SubmitAndWaitForTransactionTreeResponse.defaultInstance))
    mockCommandService
  }

}
