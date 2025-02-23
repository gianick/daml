// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.client.services.admin

import com.daml.ledger.api.v1.admin.party_management_service.PartyManagementServiceGrpc.PartyManagementServiceStub
import com.daml.ledger.api.v1.admin.party_management_service.{
  AllocatePartyRequest,
  GetParticipantIdRequest,
  GetPartiesRequest,
  ListKnownPartiesRequest,
  PartyDetails as ApiPartyDetails,
}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.Party
import com.digitalasset.canton.ledger.api.domain.{
  IdentityProviderId,
  ObjectMeta,
  ParticipantId,
  PartyDetails,
}
import com.digitalasset.canton.ledger.client.LedgerClient
import scalaz.OneAnd

import scala.concurrent.{ExecutionContext, Future}

object PartyManagementClient {

  private def details(d: ApiPartyDetails): PartyDetails =
    PartyDetails(
      Party.assertFromString(d.party),
      if (d.displayName.isEmpty) None else Some(d.displayName),
      d.isLocal,
      ObjectMeta.empty,
      IdentityProviderId(d.identityProviderId),
    )

  private val getParticipantIdRequest = GetParticipantIdRequest()

  private val listKnownPartiesRequest = ListKnownPartiesRequest()

  private def getPartiesRequest(parties: OneAnd[Set, Ref.Party]) = {
    import scalaz.std.iterable.*
    import scalaz.syntax.foldable.*
    GetPartiesRequest(parties.toList)
  }
}

final class PartyManagementClient(service: PartyManagementServiceStub)(implicit
    ec: ExecutionContext
) {

  def getParticipantId(token: Option[String] = None): Future[ParticipantId] =
    LedgerClient
      .stub(service, token)
      .getParticipantId(PartyManagementClient.getParticipantIdRequest)
      .map(r => ParticipantId(Ref.ParticipantId.assertFromString(r.participantId)))

  def listKnownParties(token: Option[String] = None): Future[List[PartyDetails]] =
    LedgerClient
      .stub(service, token)
      .listKnownParties(PartyManagementClient.listKnownPartiesRequest)
      .map(_.partyDetails.view.map(PartyManagementClient.details).toList)

  def getParties(
      parties: OneAnd[Set, Ref.Party],
      token: Option[String] = None,
  ): Future[List[PartyDetails]] =
    LedgerClient
      .stub(service, token)
      .getParties(PartyManagementClient.getPartiesRequest(parties))
      .map(_.partyDetails.view.map(PartyManagementClient.details).toList)

  def allocateParty(
      hint: Option[String],
      displayName: Option[String],
      token: Option[String] = None,
  ): Future[PartyDetails] =
    LedgerClient
      .stub(service, token)
      .allocateParty(new AllocatePartyRequest(hint.getOrElse(""), displayName.getOrElse("")))
      .map(_.partyDetails.getOrElse(sys.error("No PartyDetails in response.")))
      .map(PartyManagementClient.details)
}
