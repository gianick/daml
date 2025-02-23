// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.traffic

import cats.data.EitherT
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.sequencing.TrafficControlParameters
import com.digitalasset.canton.sequencing.protocol.{
  Batch,
  ClosedEnvelope,
  GroupRecipient,
  TrafficState,
}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

/** Holds the traffic control state and control rate limiting logic of members of a sequencer
  */
trait SequencerRateLimitManager {

  /** Create a traffic state for a new member at the given timestamp.
    * Its base traffic remainder will be equal to the max burst window configured at that point in time.
    */
  def createNewTrafficStateAt(
      member: Member,
      timestamp: CantonTimestamp,
      trafficControlConfig: TrafficControlParameters,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): FutureUnlessShutdown[TrafficState]

  /** Consume the traffic costs of the submission request from the sender's traffic state.
    *
    * NOTE: This method must be called in order of the sequencing timestamps.
    */
  def consume(
      sender: Member,
      batch: Batch[ClosedEnvelope],
      sequencingTimestamp: CantonTimestamp,
      trafficState: TrafficState,
      trafficControlConfig: TrafficControlParameters,
      groupToMembers: Map[GroupRecipient, Set[Member]],
      lastBalanceUpdateTimestamp: Option[CantonTimestamp] = None,
      warnIfApproximate: Boolean = true,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): EitherT[
    FutureUnlessShutdown,
    SequencerRateLimitError,
    TrafficState,
  ]

  /** Returns the provided states updated at updateTimestamp.
    * Note that if updateTimestamp is older than the timestamp of the traffic state of a member, the state for that member will not be updated.
    *  If updateTimestamp is not provided, the latest timestamp at which the traffic balance is known will be used.
    * Specifically, the remaining base traffic and the traffic balance may have changed since the provided traffic state.
    * @param partialTrafficStates the traffic states to update
    * @param updateTimestamp the timestamp at which the traffic states should be updated. If not provided, the latest timestamp at which balances are known will be used.
    * @param trafficControlParameters the traffic control parameters
    * @param lastBalanceUpdateTimestamp latest known timestamp which may contain a balance update
    * @param warnIfApproximate if true, a warning will be logged if the balance is approximate
    */
  def updateTrafficStates(
      partialTrafficStates: Map[Member, TrafficState],
      updateTimestamp: Option[CantonTimestamp],
      trafficControlParameters: TrafficControlParameters,
      lastBalanceUpdateTimestamp: Option[CantonTimestamp],
      warnIfApproximate: Boolean,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): FutureUnlessShutdown[Map[Member, TrafficState]]
}

sealed trait SequencerRateLimitError

object SequencerRateLimitError {
  final case class AboveTrafficLimit(
      member: Member,
      trafficCost: NonNegativeLong,
      trafficState: TrafficState,
  ) extends SequencerRateLimitError

  final case class UnknownBalance(
      member: Member,
      timestamp: CantonTimestamp,
  ) extends SequencerRateLimitError
}
