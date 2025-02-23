// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.traffic

import cats.data.EitherT
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.sequencer.traffic.SequencerRateLimitError
import com.digitalasset.canton.domain.sequencing.traffic.EnterpriseSequencerRateLimitManager.BalanceUpdateClient
import com.digitalasset.canton.lifecycle.{FutureUnlessShutdown, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

/** Implementation of BalanceUpdateClient that uses the TrafficBalanceManager to get the traffic balance.
  */
class BalanceUpdateClientImpl(
    manager: TrafficBalanceManager,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends BalanceUpdateClient
    with NamedLogging {
  override def apply(
      member: Member,
      timestamp: CantonTimestamp,
      lastSeen: Option[CantonTimestamp],
      warnIfApproximate: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, SequencerRateLimitError, NonNegativeLong] = {
    manager
      .getTrafficBalanceAt(member, timestamp, lastSeen, warnIfApproximate)
      .map(_.map(_.balance).getOrElse(NonNegativeLong.zero))
      .leftMap { case TrafficBalanceManager.TrafficBalanceAlreadyPruned(member, timestamp) =>
        SequencerRateLimitError.UnknownBalance(member, timestamp)
      }
  }
  override def lastKnownTimestamp: Option[CantonTimestamp] = manager.maxTsO
  override def close(): Unit = Lifecycle.close(manager)(logger)
}
