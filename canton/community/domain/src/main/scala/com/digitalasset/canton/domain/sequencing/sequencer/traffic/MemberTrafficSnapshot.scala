// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.traffic

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.admin.v30.SequencerSnapshot.MemberTrafficSnapshot as MemberTrafficSnapshotP
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.topology.Member

/** Traffic information used to initialize a sequencer
  */
final case class MemberTrafficSnapshot(
    member: Member,
    state: TrafficState,
) {
  def toProtoV30: MemberTrafficSnapshotP = {
    MemberTrafficSnapshotP(
      member = member.toProtoPrimitive,
      extraTrafficRemainder = state.extraTrafficRemainder.value,
      extraTrafficConsumed = state.extraTrafficConsumed.value,
      baseTrafficRemainder = state.baseTrafficRemainder.value,
      sequencingTimestamp = state.timestamp.toProtoPrimitive,
    )
  }
}

object MemberTrafficSnapshot {
  def fromProtoV30(
      snapshotP: MemberTrafficSnapshotP
  ): ParsingResult[MemberTrafficSnapshot] = {
    for {
      member <- Member.fromProtoPrimitive(snapshotP.member, "member")
      extraTrafficRemainder <- ProtoConverter.parseNonNegativeLong(snapshotP.extraTrafficRemainder)
      extraTrafficConsumed <- ProtoConverter.parseNonNegativeLong(snapshotP.extraTrafficConsumed)
      baseTrafficRemainder <- ProtoConverter.parseNonNegativeLong(snapshotP.baseTrafficRemainder)
      timestamp <- CantonTimestamp.fromProtoPrimitive(snapshotP.sequencingTimestamp)
    } yield MemberTrafficSnapshot(
      member,
      TrafficState(
        extraTrafficRemainder = extraTrafficRemainder,
        extraTrafficConsumed = extraTrafficConsumed,
        baseTrafficRemainder = baseTrafficRemainder,
        timestamp = timestamp,
      ),
    )
  }
}
