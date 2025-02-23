// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.validation

import com.daml.error.{ContextualizedErrorLogger, NoLogging}
import com.daml.ledger.api.v1.transaction_filter.{
  Filters,
  InclusiveFilters,
  InterfaceFilter,
  TemplateFilter,
}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset.ParticipantBoundary
import com.daml.ledger.api.v2.state_service.GetLedgerEndRequest
import com.daml.ledger.api.v2.transaction_filter.*
import com.daml.ledger.api.v2.update_service.{
  GetTransactionByEventIdRequest,
  GetTransactionByIdRequest,
  GetUpdatesRequest,
}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.TypeConRef
import com.digitalasset.canton.ledger.api.domain
import io.grpc.Status.Code.*
import org.mockito.MockitoSugar
import org.scalatest.wordspec.AnyWordSpec

class UpdateServiceRequestValidatorTest
    extends AnyWordSpec
    with ValidatorTestUtils
    with MockitoSugar {
  private implicit val noLogging: ContextualizedErrorLogger = NoLogging

  private val templateId = Identifier(packageId, includedModule, includedTemplate)

  private def txReqBuilder(templateIdsForParty: Seq[Identifier]) = GetUpdatesRequest(
    Some(
      ParticipantOffset(ParticipantOffset.Value.Boundary(ParticipantBoundary.PARTICIPANT_BEGIN))
    ),
    Some(ParticipantOffset(ParticipantOffset.Value.Absolute(absoluteOffset))),
    Some(
      TransactionFilter(
        Map(
          party ->
            Filters(
              Some(
                InclusiveFilters(
                  templateFilters = templateIdsForParty.map(tId => TemplateFilter(Some(tId))),
                  interfaceFilters = Seq(
                    InterfaceFilter(
                      interfaceId = Some(
                        Identifier(
                          packageId,
                          moduleName = includedModule,
                          entityName = includedTemplate,
                        )
                      ),
                      includeInterfaceView = true,
                      includeCreatedEventBlob = true,
                    )
                  ),
                )
              )
            )
        )
      )
    ),
    verbose,
  )
  private val txReq = txReqBuilder(Seq(templateId))
  private val txReqWithPackageNameScoping = txReqBuilder(
    Seq(templateId.copy(packageId = Ref.PackageRef.Name(packageName).toString))
  )

  private val txTreeReq = GetUpdatesRequest(
    Some(
      ParticipantOffset(ParticipantOffset.Value.Boundary(ParticipantBoundary.PARTICIPANT_BEGIN))
    ),
    Some(ParticipantOffset(ParticipantOffset.Value.Absolute(absoluteOffset))),
    Some(TransactionFilter(Map(party -> Filters.defaultInstance))),
    verbose,
  )

  private val endReq = GetLedgerEndRequest()

  private val txByEvIdReq =
    GetTransactionByEventIdRequest(eventId, Seq(party))

  private val txByIdReq =
    GetTransactionByIdRequest(transactionId, Seq(party))

  private val transactionFilterValidator = new TransactionFilterValidator(upgradingEnabled = false)

  private val validator = new UpdateServiceRequestValidator(
    new PartyValidator(PartyNameChecker.AllowAllParties),
    transactionFilterValidator,
  )

  private val transactionFilterValidatorUpgradingEnabled = new TransactionFilterValidator(
    upgradingEnabled = true
  )

  private val validatorUpgradingEnabled = new UpdateServiceRequestValidator(
    new PartyValidator(PartyNameChecker.AllowAllParties),
    transactionFilterValidatorUpgradingEnabled,
  )

  "TransactionRequestValidation" when {

    "validating regular requests" should {

      "accept simple requests" in {
        inside(validator.validate(txReq, ledgerEnd)) { case Right(req) =>
          req.startExclusive shouldBe domain.ParticipantOffset.ParticipantBegin
          req.endInclusive shouldBe Some(domain.ParticipantOffset.Absolute(absoluteOffset))
          val filtersByParty = req.filter.filtersByParty
          filtersByParty should have size 1
          hasExpectedFilters(req)
          req.verbose shouldEqual verbose
        }

      }

      "return the correct error on missing filter" in {
        requestMustFailWith(
          validator.validate(txReq.update(_.optionalFilter := None), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: filter",
          metadata = Map.empty,
        )
      }

      "return the correct error on empty filter" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.update(_.filter.filtersByParty := Map.empty),
            ledgerEnd,
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: filtersByParty cannot be empty",
          metadata = Map.empty,
        )
      }

      "return the correct error on empty interfaceId in interfaceFilter" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.update(_.filter.filtersByParty.modify(_.map { case (p, f) =>
              p -> f.update(_.inclusive := InclusiveFilters(Seq(InterfaceFilter(None, true))))
            })),
            ledgerEnd,
          ),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: interfaceId",
          metadata = Map.empty,
        )
      }

      "return the correct error on missing begin" in {
        requestMustFailWith(
          request = validator.validate(txReq.update(_.optionalBeginExclusive := None), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: begin",
          metadata = Map.empty,
        )
      }

      "return the correct error on empty begin " in {
        requestMustFailWith(
          request =
            validator.validate(txReq.update(_.beginExclusive := ParticipantOffset()), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: begin.(boundary|value)",
          metadata = Map.empty,
        )
      }

      "return the correct error on empty end " in {
        requestMustFailWith(
          request = validator.validate(txReq.withEndInclusive(ParticipantOffset()), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: end.(boundary|value)",
          metadata = Map.empty,
        )
      }

      "return the correct error on unknown begin boundary" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.withBeginExclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Boundary(ParticipantBoundary.Unrecognized(7))
              )
            ),
            ledgerEnd,
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown ledger boundary value '7' in field begin.boundary",
          metadata = Map.empty,
        )
      }

      "return the correct error on unknown end boundary" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.withEndInclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Boundary(ParticipantBoundary.Unrecognized(7))
              )
            ),
            ledgerEnd,
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown ledger boundary value '7' in field end.boundary",
          metadata = Map.empty,
        )
      }

      "return the correct error when begin offset is after ledger end" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.withBeginExclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Absolute((ledgerEnd.value.toInt + 1).toString)
              )
            ),
            ledgerEnd,
          ),
          code = OUT_OF_RANGE,
          description =
            "OFFSET_AFTER_LEDGER_END(12,0): Begin offset (1001) is after ledger end (1000)",
          metadata = Map.empty,
        )
      }

      "return the correct error when end offset is after ledger end" in {
        requestMustFailWith(
          request = validator.validate(
            txReq.withEndInclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Absolute((ledgerEnd.value.toInt + 1).toString)
              )
            ),
            ledgerEnd,
          ),
          code = OUT_OF_RANGE,
          description =
            "OFFSET_AFTER_LEDGER_END(12,0): End offset (1001) is after ledger end (1000)",
          metadata = Map.empty,
        )
      }

      "tolerate missing end" in {
        inside(validator.validate(txReq.update(_.optionalEndInclusive := None), ledgerEnd)) {
          case Right(req) =>
            req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
            req.endInclusive shouldEqual None
            val filtersByParty = req.filter.filtersByParty
            filtersByParty should have size 1
            hasExpectedFilters(req)
            req.verbose shouldEqual verbose
        }
      }

      "tolerate empty filters_inclusive" in {
        inside(
          validator.validate(
            txReq.update(_.filter.filtersByParty.modify(_.map { case (p, f) =>
              p -> f.update(_.inclusive := InclusiveFilters(Nil, Nil))
            })),
            ledgerEnd,
          )
        ) { case Right(req) =>
          req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
          req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
          val filtersByParty = req.filter.filtersByParty
          filtersByParty should have size 1
          inside(filtersByParty.headOption.value) { case (p, filters) =>
            p shouldEqual party
            filters shouldEqual domain.Filters(Some(domain.InclusiveFilters(Set(), Set())))
          }
          req.verbose shouldEqual verbose
        }
      }

      "tolerate missing filters_inclusive" in {
        inside(
          validator.validate(
            txReq.update(_.filter.filtersByParty.modify(_.map { case (p, f) =>
              p -> f.update(_.optionalInclusive := None)
            })),
            ledgerEnd,
          )
        ) { case Right(req) =>
          req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
          req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
          val filtersByParty = req.filter.filtersByParty
          filtersByParty should have size 1
          inside(filtersByParty.headOption.value) { case (p, filters) =>
            p shouldEqual party
            filters shouldEqual domain.Filters(None)
          }
          req.verbose shouldEqual verbose
        }
      }

      "tolerate all fields filled out" in {
        inside(validator.validate(txReq, ledgerEnd)) { case Right(req) =>
          req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
          req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
          hasExpectedFilters(req)
          req.verbose shouldEqual verbose
        }
      }

      "when upgrading disabled" should {
        "disallow usage of package-name-scoped templates" in {
          requestMustFailWith(
            request = validator.validate(txReqWithPackageNameScoping, ledgerEnd),
            code = INVALID_ARGUMENT,
            description =
              "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: package-name scoping for requests is only possible when smart contract upgrading feature is enabled",
            metadata = Map.empty,
          )
        }
      }

      "when upgrading enabled" should {
        "allow package-name scoped templates" in {
          inside(validatorUpgradingEnabled.validate(txReqWithPackageNameScoping, ledgerEnd)) {
            case Right(req) =>
              req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
              req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
              hasExpectedFilters(
                req,
                expectedTemplates =
                  Set(Ref.TypeConRef(Ref.PackageRef.Name(packageName), templateQualifiedName)),
              )
              req.verbose shouldEqual verbose
          }
        }

        "still allow populated packageIds in templateIds (for backwards compatibility)" in {
          inside(validatorUpgradingEnabled.validate(txReq, ledgerEnd)) { case Right(req) =>
            req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
            req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
            hasExpectedFilters(req)
            req.verbose shouldEqual verbose
          }
        }
      }

      "current definition populate the right domain request" in {
        val result = validator.validate(
          txReqBuilder(Seq.empty).copy(
            filter = Some(
              TransactionFilter(
                Map(
                  party -> Filters(
                    Some(
                      InclusiveFilters(
                        interfaceFilters = Seq(
                          InterfaceFilter(
                            interfaceId = Some(templateId),
                            includeInterfaceView = true,
                            includeCreatedEventBlob = true,
                          )
                        ),
                        templateFilters = Seq(TemplateFilter(Some(templateId), true)),
                      )
                    )
                  )
                )
              )
            )
          ),
          ledgerEnd,
        )
        result.map(_.filter.filtersByParty) shouldBe Right(
          Map(
            party -> domain.Filters(
              Some(
                domain.InclusiveFilters(
                  templateFilters = Set(
                    domain.TemplateFilter(
                      TypeConRef.assertFromString("packageId:includedModule:includedTemplate"),
                      true,
                    )
                  ),
                  interfaceFilters = Set(
                    domain.InterfaceFilter(
                      interfaceId = Ref.Identifier.assertFromString(
                        "packageId:includedModule:includedTemplate"
                      ),
                      includeView = true,
                      includeCreatedEventBlob = true,
                    )
                  ),
                )
              )
            )
          )
        )
      }
    }

    "validating tree requests" should {

      "tolerate missing filters_inclusive" in {
        inside(validator.validateTree(txTreeReq, ledgerEnd)) { case Right(req) =>
          req.startExclusive shouldEqual domain.ParticipantOffset.ParticipantBegin
          req.endInclusive shouldEqual Some(domain.ParticipantOffset.Absolute(absoluteOffset))
          req.parties should have size 1
          req.parties.headOption.value shouldEqual party
          req.verbose shouldEqual verbose
        }
      }

      "not tolerate having filters_inclusive" in {
        requestMustFailWith(
          request = validator.validateTree(
            txTreeReq.update(_.filter.filtersByParty.modify(_.map { case (p, f) =>
              p -> f.update(_.optionalInclusive := Some(InclusiveFilters()))
            })),
            ledgerEnd,
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: party attempted subscription for templates. Template filtration is not supported on GetTransactionTrees RPC. To get filtered data, use the GetTransactions RPC.",
          metadata = Map.empty,
        )
      }

      "return the correct error when begin offset is after ledger end" in {
        requestMustFailWith(
          request = validator.validateTree(
            txTreeReq.withBeginExclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Absolute((ledgerEnd.value.toInt + 1).toString)
              )
            ),
            ledgerEnd,
          ),
          code = OUT_OF_RANGE,
          description =
            "OFFSET_AFTER_LEDGER_END(12,0): Begin offset (1001) is after ledger end (1000)",
          metadata = Map.empty,
        )
      }

      "return the correct error when end offset is after ledger end" in {
        requestMustFailWith(
          request = validator.validateTree(
            txTreeReq.withEndInclusive(
              ParticipantOffset(
                ParticipantOffset.Value.Absolute((ledgerEnd.value.toInt + 1).toString)
              )
            ),
            ledgerEnd,
          ),
          code = OUT_OF_RANGE,
          description =
            "OFFSET_AFTER_LEDGER_END(12,0): End offset (1001) is after ledger end (1000)",
          metadata = Map.empty,
        )
      }
    }

    "validating transaction by id requests" should {

      "fail on empty transactionId" in {
        requestMustFailWith(
          request = validator.validateTransactionById(txByIdReq.withUpdateId("")),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: update_id",
          metadata = Map.empty,
        )
      }

      "fail on empty requesting parties" in {
        requestMustFailWith(
          request = validator.validateTransactionById(txByIdReq.withRequestingParties(Nil)),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: requesting_parties",
          metadata = Map.empty,
        )
      }

    }

    "validating transaction by event id requests" should {

      "fail on empty eventId" in {
        requestMustFailWith(
          request = validator.validateTransactionByEventId(txByEvIdReq.withEventId("")),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: event_id",
          metadata = Map.empty,
        )
      }

      "fail on empty requesting parties" in {
        requestMustFailWith(
          request = validator.validateTransactionByEventId(txByEvIdReq.withRequestingParties(Nil)),
          code = INVALID_ARGUMENT,
          description =
            "MISSING_FIELD(8,0): The submitted command is missing a mandatory field: requesting_parties",
          metadata = Map.empty,
        )
      }

    }

    "applying party name checks" should {

      val partyRestrictiveValidator = new UpdateServiceRequestValidator(
        new PartyValidator(PartyNameChecker.AllowPartySet(Set(party))),
        transactionFilterValidator,
      )

      val partyWithUnknowns = List("party", "Alice", "Bob")
      val filterWithUnknown =
        TransactionFilter(partyWithUnknowns.map(_ -> Filters.defaultInstance).toMap)
      val filterWithKnown =
        TransactionFilter(Map(party -> Filters.defaultInstance))

      "reject transaction requests for unknown parties" in {
        requestMustFailWith(
          request =
            partyRestrictiveValidator.validate(txReq.withFilter(filterWithUnknown), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown parties: [Alice, Bob]",
          metadata = Map.empty,
        )
      }

      "reject transaction tree requests for unknown parties" in {
        requestMustFailWith(
          request = partyRestrictiveValidator
            .validateTree(txTreeReq.withFilter(filterWithUnknown), ledgerEnd),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown parties: [Alice, Bob]",
          metadata = Map.empty,
        )
      }

      "reject transaction by id requests for unknown parties" in {
        requestMustFailWith(
          request = partyRestrictiveValidator.validateTransactionById(
            txByIdReq.withRequestingParties(partyWithUnknowns)
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown parties: [Alice, Bob]",
          metadata = Map.empty,
        )
      }

      "reject transaction by event id requests for unknown parties" in {
        requestMustFailWith(
          request = partyRestrictiveValidator.validateTransactionById(
            txByIdReq.withRequestingParties(partyWithUnknowns)
          ),
          code = INVALID_ARGUMENT,
          description =
            "INVALID_ARGUMENT(8,0): The submitted command has invalid arguments: Unknown parties: [Alice, Bob]",
          metadata = Map.empty,
        )
      }

      "accept transaction requests for known parties" in {
        partyRestrictiveValidator.validate(
          txReq.withFilter(filterWithKnown),
          ledgerEnd,
        ) shouldBe a[Right[_, _]]
      }

      "accept transaction tree requests for known parties" in {
        partyRestrictiveValidator.validateTree(
          txTreeReq.withFilter(filterWithKnown),
          ledgerEnd,
        ) shouldBe a[Right[_, _]]
      }

      "accept transaction by id requests for known parties" in {
        partyRestrictiveValidator.validateTransactionById(
          txByIdReq.withRequestingParties(List("party"))
        ) shouldBe a[Right[_, _]]
      }

      "accept transaction by event id requests for known parties" in {
        partyRestrictiveValidator.validateTransactionById(
          txByIdReq.withRequestingParties(List("party"))
        ) shouldBe a[Right[_, _]]
      }
    }
  }
}
