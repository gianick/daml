// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool.submission

import com.codahale.metrics.MetricRegistry
import com.daml.ledger.api.benchtool.config.WorkflowConfig
import com.daml.ledger.api.benchtool.config.WorkflowConfig.FooSubmissionConfig.{
  ConsumingExercises,
  NonconsumingExercises,
}
import com.daml.ledger.api.benchtool.metrics.MetricsManager.NoOpMetricsManager
import com.daml.ledger.api.benchtool.services.LedgerApiServices
import com.daml.ledger.api.testing.utils.SuiteResourceManagementAroundAll
import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.platform.sandbox.fixture.SandboxFixture
import org.scalactic.TripleEqualsSupport
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class FooCommandSubmitterITSpec
    extends AsyncFlatSpec
    with SandboxFixture
    with SuiteResourceManagementAroundAll
    with Matchers
    with AppendedClues {

  it should "populate participant with create, consuming and non consuming exercises" in {

    val foo1Config = WorkflowConfig.FooSubmissionConfig.ContractDescription(
      template = "Foo1",
      weight = 1,
      payloadSizeBytes = 100,
    )
    val foo2Config = WorkflowConfig.FooSubmissionConfig.ContractDescription(
      template = "Foo2",
      weight = 1,
      payloadSizeBytes = 100,
    )
    val consumingExercisesConfig = ConsumingExercises(
      probability = 1.0,
      payloadSizeBytes = 100,
    )
    val nonConsumingExercisesConfig = NonconsumingExercises(
      probability = 2.0,
      payloadSizeBytes = 100,
    )
    val config = WorkflowConfig.FooSubmissionConfig(
      numberOfInstances = 10,
      numberOfObservers = 1,
      uniqueParties = false,
      instanceDistribution = List(
        foo1Config,
        foo2Config,
      ),
      nonConsumingExercises = Some(nonConsumingExercisesConfig),
      consumingExercises = Some(consumingExercisesConfig),
    )

    for {
      ledgerApiServicesF <- LedgerApiServices.forChannel(
        channel = channel,
        authorizationHelper = None,
      )
      apiServices = ledgerApiServicesF("someUser")
      tested = CommandSubmitter(
        names = new Names(),
        benchtoolUserServices = apiServices,
        adminServices = apiServices,
        metricRegistry = new MetricRegistry,
        metricsManager = NoOpMetricsManager(),
      )
      (signatory, observers) <- tested.prepare(config)
      generator: CommandGenerator = new FooCommandGenerator(
        randomnessProvider = RandomnessProvider.Default,
        signatory = signatory,
        config = config,
        allObservers = observers,
      )
      _ <- tested.generateAndSubmit(
        generator = generator,
        config = config,
        signatory = signatory,
        maxInFlightCommands = 1,
        submissionBatchSize = 5,
      )
      eventsObserver = TreeEventsObserver(expectedTemplateNames = Set("Foo1", "Foo2"))
      _ <- apiServices.transactionService.transactionTrees(
        config = WorkflowConfig.StreamConfig.TransactionTreesStreamConfig(
          name = "dummy-name",
          filters = List(
            WorkflowConfig.StreamConfig.PartyFilter(
              party = signatory.toString,
              templates = List.empty,
            )
          ),
          beginOffset = None,
          endOffset = Some(LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_END)),
          objectives = None,
        ),
        observer = eventsObserver,
      )
      observerResult: ObservedEvents <- eventsObserver.result
    } yield {
      observerResult.createEvents.size shouldBe config.numberOfInstances withClue ("number of create events")

      observerResult.avgSizeOfCreateEventPerTemplateName("Foo1") shouldBe roughly(
        foo1Config.payloadSizeBytes * 2,
        toleranceMul = 0.5,
      ) withClue ("payload size of create Foo1")
      observerResult.avgSizeOfCreateEventPerTemplateName("Foo2") shouldBe roughly(
        foo2Config.payloadSizeBytes * 2,
        toleranceMul = 0.5,
      ) withClue ("payload size of create Foo2")
      observerResult.avgSizeOfConsumingExercise shouldBe roughly(
        consumingExercisesConfig.payloadSizeBytes * 2,
        toleranceMul = 0.5,
      )
      observerResult.avgSizeOfNonconsumingExercise shouldBe roughly(
        nonConsumingExercisesConfig.payloadSizeBytes * 2,
        toleranceMul = 0.5,
      )

      observerResult.consumingExercises.size.toDouble shouldBe (config.numberOfInstances * consumingExercisesConfig.probability) withClue ("number of consuming exercises")
      observerResult.nonConsumingExercises.size.toDouble shouldBe (config.numberOfInstances * nonConsumingExercisesConfig.probability) withClue ("number of non consuming exercises")

      succeed
    }
  }

  private def roughly(n: Int, toleranceMul: Double): TripleEqualsSupport.Spread[Int] = {
    require(toleranceMul > 0.0)
    val skew = Math.round(toleranceMul * n).toInt
    n +- skew
  }

}