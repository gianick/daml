// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  BatchingConfig,
  CachingConfigs,
  ProcessingTimeout,
  TopologyXConfig,
}
import com.digitalasset.canton.crypto.{Crypto, CryptoPureApi}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.config.ParticipantStoreConfig
import com.digitalasset.canton.participant.store.EventLogId.DomainEventLogId
import com.digitalasset.canton.participant.store.db.DbSyncDomainPersistentStateX
import com.digitalasset.canton.participant.store.memory.InMemorySyncDomainPersistentStateX
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.store.*
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.store.TopologyStoreId.DomainStore
import com.digitalasset.canton.topology.store.TopologyStoreX
import com.digitalasset.canton.topology.{DomainOutboxQueue, DomainTopologyManagerX}
import com.digitalasset.canton.version.ProtocolVersion

import scala.concurrent.ExecutionContext

/** The state of a synchronization domain that is independent of the connectivity to the domain. */
trait SyncDomainPersistentState extends NamedLogging with AutoCloseable {

  protected[participant] def loggerFactory: NamedLoggerFactory

  /** The crypto operations used on the domain */
  def pureCryptoApi: CryptoPureApi
  def domainId: IndexedDomain
  def protocolVersion: ProtocolVersion
  def enableAdditionalConsistencyChecks: Boolean
  def eventLog: SingleDimensionEventLog[DomainEventLogId]
  def contractStore: ContractStore
  def transferStore: TransferStore
  def activeContractStore: ActiveContractStore
  def sequencedEventStore: SequencedEventStore
  def sequencerCounterTrackerStore: SequencerCounterTrackerStore
  def sendTrackerStore: SendTrackerStore
  def requestJournalStore: RequestJournalStore
  def acsCommitmentStore: AcsCommitmentStore
  def parameterStore: DomainParameterStore
  def submissionTrackerStore: SubmissionTrackerStore
  def isMemory: Boolean

  def topologyStore: TopologyStoreX[DomainStore]
  def topologyManager: DomainTopologyManagerX
  def domainOutboxQueue: DomainOutboxQueue
}

object SyncDomainPersistentState {

  def createX(
      storage: Storage,
      domainId: IndexedDomain,
      protocolVersion: ProtocolVersion,
      clock: Clock,
      crypto: Crypto,
      parameters: ParticipantStoreConfig,
      topologyXConfig: TopologyXConfig,
      caching: CachingConfigs,
      batching: BatchingConfig,
      processingTimeouts: ProcessingTimeout,
      enableAdditionalConsistencyChecks: Boolean,
      indexedStringStore: IndexedStringStore,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
  )(implicit ec: ExecutionContext): SyncDomainPersistentState = {
    val domainLoggerFactory = loggerFactory.append("domainId", domainId.domainId.toString)
    storage match {
      case _: MemoryStorage =>
        new InMemorySyncDomainPersistentStateX(
          clock,
          crypto,
          domainId,
          protocolVersion,
          enableAdditionalConsistencyChecks,
          topologyXConfig.enableTopologyTransactionValidation,
          domainLoggerFactory,
          processingTimeouts,
          futureSupervisor,
        )
      case db: DbStorage =>
        new DbSyncDomainPersistentStateX(
          domainId,
          protocolVersion,
          clock,
          db,
          crypto,
          parameters,
          caching,
          batching,
          processingTimeouts,
          enableAdditionalConsistencyChecks,
          topologyXConfig.enableTopologyTransactionValidation,
          indexedStringStore,
          domainLoggerFactory,
          futureSupervisor,
        )
    }
  }

}
