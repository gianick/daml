// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.validation

import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.api.v1.transaction_filter.Filters
import com.daml.ledger.api.v2.transaction_filter.TransactionFilter
import com.daml.ledger.api.v2.update_service.{
  GetTransactionByEventIdRequest,
  GetTransactionByIdRequest,
  GetUpdatesRequest,
}
import com.daml.lf.data.Ref
import com.digitalasset.canton.ledger.api.domain
import com.digitalasset.canton.ledger.api.domain.ParticipantOffset
import com.digitalasset.canton.ledger.api.messages.transaction
import com.digitalasset.canton.ledger.api.messages.transaction.GetUpdateTreesRequest
import com.digitalasset.canton.ledger.api.validation.ValueValidator.*
import io.grpc.StatusRuntimeException

object UpdateServiceRequestValidator {
  type Result[X] = Either[StatusRuntimeException, X]

}
class UpdateServiceRequestValidator(
    partyValidator: PartyValidator,
    transactionFilterValidator: TransactionFilterValidator,
) {

  import FieldValidator.*
  import UpdateServiceRequestValidator.Result
  import ValidationErrors.invalidArgument

  case class PartialValidation(
      transactionFilter: TransactionFilter,
      begin: domain.ParticipantOffset,
      end: Option[domain.ParticipantOffset],
      knownParties: Set[Ref.Party],
  )

  private def commonValidations(
      req: GetUpdatesRequest
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger): Result[PartialValidation] = {
    for {
      filter <- requirePresence(req.filter, "filter")
      requiredBegin <- requirePresence(req.beginExclusive, "begin")
      convertedBegin <- ParticipantOffsetValidator.validate(requiredBegin, "begin")
      convertedEnd <- ParticipantOffsetValidator.validateOptional(req.endInclusive, "end")
      knownParties <- partyValidator.requireKnownParties(req.getFilter.filtersByParty.keySet)
    } yield PartialValidation(
      filter,
      convertedBegin,
      convertedEnd,
      knownParties,
    )

  }

  def validate(
      req: GetUpdatesRequest,
      ledgerEnd: ParticipantOffset.Absolute,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[transaction.GetTransactionsRequest] = {

    for {
      partial <- commonValidations(req)
      _ <- ParticipantOffsetValidator.offsetIsBeforeEndIfAbsolute(
        "Begin",
        partial.begin,
        ledgerEnd,
      )
      _ <- ParticipantOffsetValidator.offsetIsBeforeEndIfAbsolute(
        "End",
        partial.end,
        ledgerEnd,
      )
      convertedFilter <- transactionFilterValidator.validate(partial.transactionFilter)
    } yield {
      transaction.GetTransactionsRequest(
        partial.begin,
        partial.end,
        convertedFilter,
        req.verbose,
      )
    }
  }

  def validateTree(
      req: GetUpdatesRequest,
      ledgerEnd: ParticipantOffset.Absolute,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[GetUpdateTreesRequest] = {

    for {
      partial <- commonValidations(req)
      _ <- ParticipantOffsetValidator.offsetIsBeforeEndIfAbsolute(
        "Begin",
        partial.begin,
        ledgerEnd,
      )
      _ <- ParticipantOffsetValidator.offsetIsBeforeEndIfAbsolute(
        "End",
        partial.end,
        ledgerEnd,
      )
      convertedFilter <- transactionFilterToPartySet(partial.transactionFilter)
    } yield {
      transaction.GetUpdateTreesRequest(
        partial.begin,
        partial.end,
        convertedFilter,
        req.verbose,
      )
    }
  }

  def validateTransactionById(
      req: GetTransactionByIdRequest
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[transaction.GetTransactionByIdRequest] = {
    for {
      _ <- requireNonEmptyString(req.updateId, "update_id")
      trId <- requireLedgerString(req.updateId)
      _ <- requireNonEmpty(req.requestingParties, "requesting_parties")
      parties <- partyValidator.requireKnownParties(req.requestingParties)
    } yield {
      transaction.GetTransactionByIdRequest(
        domain.TransactionId(trId),
        parties,
      )
    }
  }

  def validateTransactionByEventId(
      req: GetTransactionByEventIdRequest
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[transaction.GetTransactionByEventIdRequest] = {
    for {
      eventId <- requireLedgerString(req.eventId, "event_id")
      _ <- requireNonEmpty(req.requestingParties, "requesting_parties")
      parties <- partyValidator.requireKnownParties(req.requestingParties)
    } yield {
      transaction.GetTransactionByEventIdRequest(
        domain.EventId(eventId),
        parties,
      )
    }
  }

  // Allow using deprecated Protobuf fields for backwards compatibility
  private def transactionFilterToPartySet(
      transactionFilter: TransactionFilter
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger) =
    transactionFilter.filtersByParty
      .collectFirst { case (party, Filters(Some(inclusive))) =>
        invalidArgument(
          s"$party attempted subscription for templates. Template filtration is not supported on GetTransactionTrees RPC. To get filtered data, use the GetTransactions RPC."
        )
      }
      .fold(partyValidator.requireKnownParties(transactionFilter.filtersByParty.keys))(Left(_))

}
