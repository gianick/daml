// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console.commands

import cats.implicits.toBifunctorOps
import cats.syntax.option.*
import cats.syntax.traverse.*
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands.Pruning.{
  GetParticipantScheduleCommand,
  SetParticipantScheduleCommand,
}
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands.Resources.{
  GetResourceLimits,
  SetResourceLimits,
}
import com.digitalasset.canton.admin.api.client.commands.*
import com.digitalasset.canton.admin.api.client.data.{
  DarMetadata,
  ListConnectedDomainsResult,
  ParticipantPruningSchedule,
}
import com.digitalasset.canton.admin.participant.v30
import com.digitalasset.canton.admin.participant.v30.PruningServiceGrpc
import com.digitalasset.canton.admin.participant.v30.PruningServiceGrpc.PruningServiceStub
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{DomainTimeTrackerConfig, NonNegativeDuration}
import com.digitalasset.canton.console.{
  AdminCommandRunner,
  BaseInspection,
  CommandFailure,
  ConsoleCommandResult,
  ConsoleEnvironment,
  ConsoleMacros,
  FeatureFlag,
  FeatureFlagFilter,
  Help,
  Helpful,
  LedgerApiCommandRunner,
  ParticipantReference,
  SequencerNodeReference,
}
import com.digitalasset.canton.crypto.SyncCryptoApiProvider
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.health.admin.data.ParticipantStatus
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.participant.ParticipantNodeCommon
import com.digitalasset.canton.participant.admin.ResourceLimits
import com.digitalasset.canton.participant.admin.grpc.TransferSearchResult
import com.digitalasset.canton.participant.admin.inspection.SyncStateInspection
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.participant.sync.TimestampedEvent
import com.digitalasset.canton.protocol.messages.{
  AcsCommitment,
  CommitmentPeriod,
  SignedProtocolMessage,
}
import com.digitalasset.canton.protocol.{LfCommittedTransaction, SerializableContract}
import com.digitalasset.canton.sequencing.{
  PossiblyIgnoredProtocolEvent,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.*
import com.digitalasset.canton.{DiscardOps, DomainAlias, SequencerAlias, config}

import java.time.Instant
import scala.concurrent.duration.Duration

sealed trait DomainChoice
object DomainChoice {
  object All extends DomainChoice
  final case class Only(aliases: Seq[DomainAlias]) extends DomainChoice
}

private[console] object ParticipantCommands {

  object dars {

    def upload(
        runner: AdminCommandRunner,
        path: String,
        vetAllPackages: Boolean,
        synchronizeVetting: Boolean,
        logger: TracedLogger,
    ): ConsoleCommandResult[String] =
      runner.adminCommand(
        ParticipantAdminCommands.Package
          .UploadDar(Some(path), vetAllPackages, synchronizeVetting, logger)
      )

  }

  object domains {

    def reference_to_config(
        domain: NonEmpty[Map[SequencerAlias, SequencerNodeReference]],
        domainAlias: DomainAlias,
        manualConnect: Boolean = false,
        maxRetryDelay: Option[NonNegativeFiniteDuration] = None,
        priority: Int = 0,
        sequencerTrustThreshold: PositiveInt = PositiveInt.one,
        submissionRequestAmplification: PositiveInt = PositiveInt.one,
    ): DomainConnectionConfig = {
      DomainConnectionConfig(
        domainAlias,
        SequencerConnections.tryMany(
          domain.toSeq.map { case (alias, domain) =>
            domain.sequencerConnection.withAlias(alias)
          },
          sequencerTrustThreshold,
          submissionRequestAmplification,
        ),
        manualConnect = manualConnect,
        None,
        priority,
        None,
        maxRetryDelay,
        DomainTimeTrackerConfig(),
      )
    }

    def to_config(
        domainAlias: DomainAlias,
        connection: String,
        manualConnect: Boolean = false,
        domainId: Option[DomainId] = None,
        certificatesPath: String = "",
        priority: Int = 0,
        initialRetryDelay: Option[NonNegativeFiniteDuration] = None,
        maxRetryDelay: Option[NonNegativeFiniteDuration] = None,
        timeTrackerConfig: DomainTimeTrackerConfig = DomainTimeTrackerConfig(),
    ): DomainConnectionConfig = {
      // architecture-handbook-entry-begin: OnboardParticipantToConfig
      val certificates = OptionUtil.emptyStringAsNone(certificatesPath).map { path =>
        BinaryFileUtil.readByteStringFromFile(path) match {
          case Left(err) => throw new IllegalArgumentException(s"failed to load ${path}: ${err}")
          case Right(bs) => bs
        }
      }
      DomainConnectionConfig.grpc(
        SequencerAlias.Default,
        domainAlias,
        connection,
        manualConnect,
        domainId,
        certificates,
        priority,
        initialRetryDelay,
        maxRetryDelay,
        timeTrackerConfig,
      )
      // architecture-handbook-entry-end: OnboardParticipantToConfig
    }

    def register(
        runner: AdminCommandRunner,
        config: DomainConnectionConfig,
        handshakeOnly: Boolean,
    ) =
      runner.adminCommand(
        ParticipantAdminCommands.DomainConnectivity
          .RegisterDomain(config, handshakeOnly = handshakeOnly)
      )

    def reconnect(runner: AdminCommandRunner, domainAlias: DomainAlias, retry: Boolean) = {
      runner.adminCommand(
        ParticipantAdminCommands.DomainConnectivity.ConnectDomain(domainAlias, retry)
      )
    }

    def list_connected(runner: AdminCommandRunner) =
      runner.adminCommand(
        ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains()
      )

    def reconnect_all(runner: AdminCommandRunner, ignoreFailures: Boolean) =
      runner.adminCommand(
        ParticipantAdminCommands.DomainConnectivity.ReconnectDomains(ignoreFailures)
      )

    def disconnect(runner: AdminCommandRunner, domainAlias: DomainAlias) =
      runner.adminCommand(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(domainAlias))

  }
}

class ParticipantTestingGroup(
    participantRef: ParticipantReference,
    val consoleEnvironment: ConsoleEnvironment,
    val loggerFactory: NamedLoggerFactory,
) extends FeatureFlagFilter
    with Helpful {
  import participantRef.*

  @Help.Summary(
    "Send a bong to a set of target parties over the ledger. Levels > 0 leads to an exploding ping with exponential number of contracts. " +
      "Throw a RuntimeException in case of failure.",
    FeatureFlag.Testing,
  )
  @Help.Description(
    """Initiates a racy ping to multiple participants,
     measuring the roundtrip time of the fastest responder, with an optional timeout.
     Grace-period is the time the bong will wait for a duplicate spent (which would indicate an error in the system) before exiting.
     If levels > 0, the ping command will lead to a binary explosion and subsequent dilation of
     contracts, where ``level`` determines the number of levels we will explode. As a result, the system will create
     (2^(L+2) - 3) contracts (where L stands for ``level``).
     Normally, only the initiator is a validator. Additional validators can be added using the validators argument.
     The bong command comes handy to run a burst test against the system and quickly leads to an overloading state."""
  )
  def bong(
      targets: Set[ParticipantId],
      validators: Set[ParticipantId] = Set(),
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.testingBong,
      levels: Int = 0,
      domainId: Option[DomainId] = None,
      workflowId: String = "",
      id: String = "",
  ): Duration = {
    consoleEnvironment.runE(
      maybe_bong(targets, validators, timeout, levels, domainId, workflowId, id)
        .toRight(
          s"Unable to bong $targets with $levels levels within ${LoggerUtil.roundDurationForHumans(timeout.duration)}"
        )
    )
  }

  @Help.Summary("Like bong, but returns None in case of failure.", FeatureFlag.Testing)
  def maybe_bong(
      targets: Set[ParticipantId],
      validators: Set[ParticipantId] = Set(),
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.testingBong,
      levels: Int = 0,
      domainId: Option[DomainId] = None,
      workflowId: String = "",
      id: String = "",
  ): Option[Duration] =
    check(FeatureFlag.Testing)(consoleEnvironment.run {
      adminCommand(
        ParticipantAdminCommands.Ping
          .Ping(
            targets.map(_.adminParty.toLf),
            validators.map(_.adminParty.toLf),
            timeout,
            levels,
            domainId,
            workflowId,
            id,
          )
      )
    }).toOption

  @Help.Summary("Fetch the current time from the given domain", FeatureFlag.Testing)
  def fetch_domain_time(
      domainAlias: DomainAlias,
      timeout: NonNegativeDuration,
  ): CantonTimestamp =
    check(FeatureFlag.Testing) {
      val id = participantRef.domains.id_of(domainAlias)
      fetch_domain_time(id, timeout)
    }

  @Help.Summary("Fetch the current time from the given domain", FeatureFlag.Testing)
  def fetch_domain_time(
      domainId: DomainId,
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ledgerCommand,
  ): CantonTimestamp =
    check(FeatureFlag.Testing) {
      consoleEnvironment.run {
        adminCommand(
          DomainTimeCommands.FetchTime(
            domainId.some,
            NonNegativeFiniteDuration.Zero,
            timeout,
          )
        )
      }.timestamp
    }

  @Help.Summary("Fetch the current time from all connected domains", FeatureFlag.Testing)
  def fetch_domain_times(
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ledgerCommand
  ): Unit =
    check(FeatureFlag.Testing) {
      participantRef.domains.list_connected().foreach { item =>
        fetch_domain_time(item.domainId, timeout).discard[CantonTimestamp]
      }
    }

  @Help.Summary("Await for the given time to be reached on the given domain", FeatureFlag.Testing)
  def await_domain_time(
      domainAlias: DomainAlias,
      time: CantonTimestamp,
      timeout: NonNegativeDuration,
  ): Unit =
    check(FeatureFlag.Testing) {
      val id = participantRef.domains.id_of(domainAlias)
      await_domain_time(id, time, timeout)
    }

  @Help.Summary("Await for the given time to be reached on the given domain", FeatureFlag.Testing)
  def await_domain_time(
      domainId: DomainId,
      time: CantonTimestamp,
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ledgerCommand,
  ): Unit =
    check(FeatureFlag.Testing) {
      consoleEnvironment.run {
        adminCommand(
          DomainTimeCommands.AwaitTime(
            domainId.some,
            time,
            timeout,
          )
        )
      }
    }
}

class LocalParticipantTestingGroup(
    participantRef: ParticipantReference with BaseInspection[ParticipantNodeCommon],
    consoleEnvironment: ConsoleEnvironment,
    loggerFactory: NamedLoggerFactory,
) extends ParticipantTestingGroup(participantRef, consoleEnvironment, loggerFactory)
    with FeatureFlagFilter
    with NoTracing {

  protected def defaultLimit: PositiveInt =
    consoleEnvironment.environment.config.parameters.console.defaultLimit

  import participantRef.*
  @Help.Summary("Lookup contracts in the Private Contract Store", FeatureFlag.Testing)
  @Help.Description("""Get raw access to the PCS of the given domain sync controller.
  The filter commands will check if the target value ``contains`` the given string.
  The arguments can be started with ``^`` such that ``startsWith`` is used for comparison or ``!`` to use ``equals``.
  The ``activeSet`` argument allows to restrict the search to the active contract set.
  """)
  def pcs_search(
      domainAlias: DomainAlias,
      // filter by id (which is txId::discriminator, so can be used to look for both)
      filterId: String = "",
      filterPackage: String = "",
      filterTemplate: String = "",
      // only include active contracts
      activeSet: Boolean = false,
      limit: PositiveInt = defaultLimit,
  ): List[(Boolean, SerializableContract)] = {
    def toOpt(str: String) = OptionUtil.emptyStringAsNone(str)

    val pcs = state_inspection
      .findContracts(
        domainAlias,
        toOpt(filterId),
        toOpt(filterPackage),
        toOpt(filterTemplate),
        limit.value,
      )
    if (activeSet) pcs.filter { case (isActive, _) => isActive }
    else pcs
  }

  @Help.Summary("Lookup of active contracts", FeatureFlag.Testing)
  def acs_search(
      domainAlias: DomainAlias,
      // filter by id (which is txId::discriminator, so can be used to look for both)
      filterId: String = "",
      filterPackage: String = "",
      filterTemplate: String = "",
      filterStakeholder: Option[PartyId] = None,
      limit: PositiveInt = defaultLimit,
  ): List[SerializableContract] = {
    val predicate = (c: SerializableContract) =>
      filterStakeholder.forall(s => c.metadata.stakeholders.contains(s.toLf))

    check(FeatureFlag.Testing) {
      pcs_search(domainAlias, filterId, filterPackage, filterTemplate, activeSet = true, limit)
        .map(_._2)
        .filter(predicate)
    }
  }

  @Help.Summary("Lookup of events", FeatureFlag.Testing)
  @Help.Description(
    """Show the event logs. To select only events from a particular domain, use the domain alias.
       Leave the domain empty to search the combined event log containing the events of all domains.
       Note that if the domain is left blank, the values of `from` and `to` cannot be set.
       This is because the combined event log isn't guaranteed to have increasing timestamps.
    """
  )
  def event_search(
      domain: Option[DomainAlias] = None,
      from: Option[Instant] = None,
      to: Option[Instant] = None,
      limit: PositiveInt = defaultLimit,
  ): Seq[(String, TimestampedEvent)] = {
    check(FeatureFlag.Testing) {
      if (domain.isEmpty && (from.isDefined || to.isDefined)) {
        logger.error(
          s"You are not allowed to set values for 'from' and 'to' if searching the combined event log " +
            s"(you are searching the combined event log because you left the domain blank)."
        )
        throw new CommandFailure()
      } else {
        stateInspection.findEvents(
          domain,
          from.map(timestampFromInstant),
          to.map(timestampFromInstant),
          Some(limit.value),
        )
      }
    }
  }

  @Help.Summary("Lookup of accepted transactions", FeatureFlag.Testing)
  @Help.Description("""Show the accepted transactions as they appear in the event logs.
       To select only transactions from a particular domain, use the domain alias.
       Leave the domain empty to search the combined event log containing the events of all domains.
       Note that if the domain is left blank, the values of `from` and `to` cannot be set.
       This is because the combined event log isn't guaranteed to have increasing timestamps.
    """)
  def transaction_search(
      domain: Option[DomainAlias] = None,
      from: Option[Instant] = None,
      to: Option[Instant] = None,
      limit: PositiveInt = defaultLimit,
  ): Seq[(String, LfCommittedTransaction)] =
    check(FeatureFlag.Testing) {
      if (domain.isEmpty && (from.isDefined || to.isDefined)) {
        logger.error(
          s"You are not allowed to set values for 'from' and 'to' if searching the combined event log " +
            s"(you are searching the combined event log because you left the domain blank)."
        )
        throw new CommandFailure()
      } else {
        stateInspection.findAcceptedTransactions(
          domain,
          from.map(timestampFromInstant),
          to.map(timestampFromInstant),
          Some(limit.value),
        )
      }
    }

  @Help.Summary("Retrieve all sequencer messages", FeatureFlag.Testing)
  @Help.Description("""Optionally allows filtering for sequencer from a certain time span (inclusive on both ends) and
      |limiting the number of displayed messages. The returned messages will be ordered on most domain ledger implementations
      |if a time span is given.
      |
      |Fails if the participant has never connected to the domain.""")
  def sequencer_messages(
      domain: DomainAlias,
      from: Option[Instant] = None,
      to: Option[Instant] = None,
      limit: PositiveInt = defaultLimit,
  ): Seq[PossiblyIgnoredProtocolEvent] =
    state_inspection.findMessages(domain, from, to, Some(limit.value))

  @Help.Summary(
    "Return the sync crypto api provider, which provides access to all cryptographic methods",
    FeatureFlag.Testing,
  )
  def crypto_api(): SyncCryptoApiProvider = check(FeatureFlag.Testing) {
    access(node => node.sync.syncCrypto)
  }

  @Help.Summary(
    "The latest timestamp before or at the given one for which no commitment is outstanding",
    FeatureFlag.Testing,
  )
  @Help.Description(
    """The latest timestamp before or at the given one for which no commitment is outstanding.
      |Note that this doesn't imply that pruning is possible at this timestamp, as the system might require some
      |additional data for crash recovery. Thus, this is useful for testing commitments; use the commands in the pruning
      |group for pruning.
      |Additionally, the result needn't fall on a "commitment tick" as specified by the reconciliation interval."""
  )
  def find_clean_commitments_timestamp(
      domain: DomainAlias,
      beforeOrAt: CantonTimestamp = CantonTimestamp.now(),
  ): Option[CantonTimestamp] =
    state_inspection.noOutstandingCommitmentsTs(domain, beforeOrAt)

  @Help.Summary(
    "Obtain access to the state inspection interface. Use at your own risk.",
    FeatureFlag.Testing,
  )
  @Help.Description(
    """The state inspection methods can fatally and permanently corrupt the state of a participant.
      |The API is subject to change in any way."""
  )
  def state_inspection: SyncStateInspection = check(FeatureFlag.Testing) { stateInspection }

  private def stateInspection: SyncStateInspection = access(node => node.sync.stateInspection)

}

class ParticipantPruningAdministrationGroup(
    runner: LedgerApiCommandRunner & AdminCommandRunner,
    consoleEnvironment: ConsoleEnvironment,
    loggerFactory: NamedLoggerFactory,
) extends PruningSchedulerAdministration(
      runner,
      consoleEnvironment,
      new PruningSchedulerCommands[PruningServiceStub](
        PruningServiceGrpc.stub,
        _.setSchedule(_),
        _.clearSchedule(_),
        _.setCron(_),
        _.setMaxDuration(_),
        _.setRetention(_),
        _.getSchedule(_),
      ),
      loggerFactory,
    )
    with FeatureFlagFilter
    with Helpful {

  import runner.*

  @Help.Summary("Prune the ledger up to the specified offset inclusively.")
  @Help.Description(
    """Prunes the participant ledger up to the specified offset inclusively returning ``Unit`` if the ledger has been
      |successfully pruned.
      |Note that upon successful pruning, subsequent attempts to read transactions via ``ledger_api.transactions.flat`` or
      |``ledger_api.transactions.trees`` or command completions via ``ledger_api.completions.list`` by specifying a begin offset
      |lower than the returned pruning offset will result in a ``NOT_FOUND`` error.
      |In the Enterprise Edition, ``prune`` performs a "full prune" freeing up significantly more space and also
      |performs additional safety checks returning a ``NOT_FOUND`` error if ``pruneUpTo`` is higher than the
      |offset returned by ``find_safe_offset`` on any domain with events preceding the pruning offset."""
  )
  def prune(pruneUpTo: ParticipantOffset): Unit =
    consoleEnvironment.run(
      ledgerApiCommand(LedgerApiCommands.ParticipantPruningService.Prune(pruneUpTo))
    )

  @Help.Summary(
    "Return the highest participant ledger offset whose record time is before or at the given one (if any) at which pruning is safely possible",
    FeatureFlag.Preview,
  )
  def find_safe_offset(beforeOrAt: Instant = Instant.now()): Option[ParticipantOffset] = {
    check(FeatureFlag.Preview) {
      val ledgerEnd = consoleEnvironment.run(
        ledgerApiCommand(LedgerApiV2Commands.StateService.LedgerEnd())
      )
      consoleEnvironment
        .run(
          adminCommand(
            ParticipantAdminCommands.Pruning
              .GetSafePruningOffsetCommand(beforeOrAt, ledgerEnd)
          )
        )
    }
  }

  @Help.Summary(
    "Prune only internal ledger state up to the specified offset inclusively.",
    FeatureFlag.Preview,
  )
  @Help.Description(
    """Special-purpose variant of the ``prune`` command only available in the Enterprise Edition that prunes only partial,
      |internal participant ledger state freeing up space not needed for serving ``ledger_api.transactions``
      |and ``ledger_api.completions`` requests. In conjunction with ``prune``, ``prune_internally`` enables pruning
      |internal ledger state more aggressively than externally observable data via the ledger api. In most use cases
      |``prune`` should be used instead. Unlike ``prune``, ``prune_internally`` has no visible effect on the Ledger API.
      |The command returns ``Unit`` if the ledger has been successfully pruned or an error if the timestamp
      |performs additional safety checks returning a ``NOT_FOUND`` error if ``pruneUpTo`` is higher than the
      |offset returned by ``find_safe_offset`` on any domain with events preceding the pruning offset."""
  )
  // Consider adding an "Enterprise" annotation if we end up having more enterprise-only commands than this lone enterprise command.
  def prune_internally(pruneUpTo: ParticipantOffset): Unit =
    check(FeatureFlag.Preview) {
      consoleEnvironment.run(
        adminCommand(ParticipantAdminCommands.Pruning.PruneInternallyCommand(pruneUpTo))
      )
    }

  @Help.Summary(
    "Activate automatic pruning according to the specified schedule with participant-specific options."
  )
  @Help.Description(
    """Refer to the ``set_schedule`` description for information about the "cron", "max_duration", and "retention"
      |parameters. Setting the "prune_internally_only" flag causes pruning to only remove internal state as described in
      |more detail in the ``prune_internally`` command description.
  """
  )
  def set_participant_schedule(
      cron: String,
      maxDuration: config.PositiveDurationSeconds,
      retention: config.PositiveDurationSeconds,
      pruneInternallyOnly: Boolean = false,
  ): Unit =
    check(FeatureFlag.Preview) {
      consoleEnvironment.run(
        runner.adminCommand(
          SetParticipantScheduleCommand(
            cron,
            maxDuration,
            retention,
            pruneInternallyOnly,
          )
        )
      )
    }

  @Help.Summary("Inspect the automatic, participant-specific pruning schedule.")
  @Help.Description(
    """The schedule consists of a "cron" expression and "max_duration" and "retention" durations as described in the
      |``get_schedule`` command description. Additionally "prune_internally" indicates if the schedule mandates
      |pruning of internal state.
  """
  )
  def get_participant_schedule(): Option[ParticipantPruningSchedule] =
    consoleEnvironment.run(
      runner.adminCommand(GetParticipantScheduleCommand())
    )

  @Help.Summary(
    "Identify the participant ledger offset to prune up to based on the specified timestamp."
  )
  @Help.Description(
    """Return the largest participant ledger offset that has been processed before or at the specified timestamp.
      |The time is measured on the participant's local clock at some point while the participant has processed the
      |the event. Returns ``None`` if no such offset exists.
    """
  )
  def get_offset_by_time(upToInclusive: Instant): Option[ParticipantOffset] =
    consoleEnvironment.run(
      adminCommand(
        ParticipantAdminCommands.Inspection.LookupOffsetByTime(
          ProtoConverter.InstantConverter.toProtoPrimitive(upToInclusive)
        )
      )
    ) match {
      case "" => None
      case offset => Some(ParticipantOffset(ParticipantOffset.Value.Absolute(offset)))
    }

  @Help.Summary("Identify the participant ledger offset to prune up to.", FeatureFlag.Preview)
  @Help.Description(
    """Return the participant ledger offset that corresponds to pruning "n" number of transactions
      |from the beginning of the ledger. Errors if the ledger holds less than "n" transactions. Specifying "n" of 1
      |returns the offset of the first transaction (if the ledger is non-empty).
    """
  )
  def locate_offset(n: Long): ParticipantOffset =
    check(FeatureFlag.Preview) {
      val rawOffset = consoleEnvironment.run(
        adminCommand(ParticipantAdminCommands.Inspection.LookupOffsetByIndex(n))
      )
      ParticipantOffset(ParticipantOffset.Value.Absolute(rawOffset))
    }

}

class LocalCommitmentsAdministrationGroup(
    runner: AdminCommandRunner with BaseInspection[ParticipantNodeCommon],
    val consoleEnvironment: ConsoleEnvironment,
    val loggerFactory: NamedLoggerFactory,
) extends FeatureFlagFilter
    with Helpful
    with NoTracing {

  import runner.*

  @Help.Summary(
    "Lookup ACS commitments received from other participants as part of the reconciliation protocol"
  )
  @Help.Description("""The arguments are:
       - domain: the alias of the domain
       - start: lowest time exclusive
       - end: highest time inclusive
       - counterParticipant: optionally filter by counter participant
      """)
  def received(
      domain: DomainAlias,
      start: Instant,
      end: Instant,
      counterParticipant: Option[ParticipantId] = None,
  ): Iterable[SignedProtocolMessage[AcsCommitment]] = {
    access(node =>
      node.sync.stateInspection
        .findReceivedCommitments(
          domain,
          timestampFromInstant(start),
          timestampFromInstant(end),
          counterParticipant,
        )
    )
  }

  @Help.Summary("Lookup ACS commitments locally computed as part of the reconciliation protocol")
  def computed(
      domain: DomainAlias,
      start: Instant,
      end: Instant,
      counterParticipant: Option[ParticipantId] = None,
  ): Iterable[(CommitmentPeriod, ParticipantId, AcsCommitment.CommitmentType)] =
    access { node =>
      node.sync.stateInspection.findComputedCommitments(
        domain,
        timestampFromInstant(start),
        timestampFromInstant(end),
        counterParticipant,
      )
    }

  def outstanding(
      domain: DomainAlias,
      start: Instant,
      end: Instant,
      counterParticipant: Option[ParticipantId] = None,
  ): Iterable[(CommitmentPeriod, ParticipantId)] =
    access { node =>
      node.sync.stateInspection.outstandingCommitments(
        domain,
        timestampFromInstant(start),
        timestampFromInstant(end),
        counterParticipant,
      )
    }

  def buffered(
      domain: DomainAlias,
      endAtOrBefore: Instant,
      counterParticipant: Option[ParticipantId] = None,
  ): Iterable[AcsCommitment] =
    access { node =>
      node.sync.stateInspection.bufferedCommitments(
        domain,
        timestampFromInstant(endAtOrBefore),
      )
    }

}

class ParticipantReplicationAdministrationGroup(
    runner: AdminCommandRunner,
    consoleEnvironment: ConsoleEnvironment,
) extends Helpful {

  @Help.Summary("Set the participant replica to passive")
  @Help.Description(
    "Trigger a graceful fail-over from this active replica to another passive replica."
  )
  def set_passive(): Unit = {
    consoleEnvironment.run {
      runner.adminCommand(
        ParticipantAdminCommands.Replication.SetPassiveCommand()
      )
    }
  }

}

/** Administration commands supported by a participant.
  */
trait ParticipantAdministration extends FeatureFlagFilter {
  this: AdminCommandRunner
    with LedgerApiCommandRunner
    with LedgerApiAdministration
    with NamedLogging =>

  import ConsoleEnvironment.Implicits.*
  implicit protected val consoleEnvironment: ConsoleEnvironment

  private val runner = this

  def id: ParticipantId

  protected def waitPackagesVetted(
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.bounded
  ): Unit

  protected def participantIsActiveOnDomain(
      domainId: DomainId,
      participantId: ParticipantId,
  ): Boolean

  @Help.Summary("Manage DAR packages")
  @Help.Group("DAR Management")
  object dars extends Helpful {
    @Help.Summary(
      "Remove a DAR from the participant",
      FeatureFlag.Preview,
    )
    @Help.Description(
      """Can be used to remove a DAR from the participant, if the following conditions are satisfied:
        |1. The main package of the DAR must be unused -- there should be no active contract from this package
        |
        |2. All package dependencies of the DAR should either be unused or contained in another of the participant node's uploaded DARs. Canton uses this restriction to ensure that the package dependencies of the DAR don't become "stranded" if they're in use.
        |
        |3. The main package of the dar should not be vetted. If it is vetted, Canton will try to automatically
        |   revoke the vetting for the main package of the DAR, but this automatic vetting revocation will only succeed if the
        |   main package vetting originates from a standard ``dars.upload``. Even if the automatic revocation fails, you can
        |   always manually revoke the package vetting.
        |
        |If synchronizeVetting is true (default), then the command will block until the participant has observed the vetting transactions to be registered with the domain.
        |"""
    )
    def remove(darHash: String, synchronizeVetting: Boolean = true): Unit = {
      check(FeatureFlag.Preview)(consoleEnvironment.run {
        adminCommand(ParticipantAdminCommands.Package.RemoveDar(darHash))
      })
      if (synchronizeVetting) {
        packages.synchronize_vetting()
      }
    }

    @Help.Summary("List installed DAR files")
    @Help.Description("""List DARs installed on this participant
      |The arguments are:
      |  filterName: filter by name (source description)
      |  limit: Limit number of results (default none)
      """)
    def list(limit: PositiveInt = defaultLimit, filterName: String = ""): Seq[v30.DarDescription] =
      consoleEnvironment
        .run {
          adminCommand(ParticipantAdminCommands.Package.ListDars(limit))
        }
        .filter(_.name.startsWith(filterName))

    @Help.Summary("List contents of DAR files")
    def list_contents(hash: String): DarMetadata = consoleEnvironment.run {
      adminCommand(
        ParticipantAdminCommands.Package.ListDarContents(hash)
      )
    }

    @Help.Summary("Upload a Dar to Canton")
    @Help.Description("""Daml code is normally shipped as a Dar archive and must explicitly be uploaded to a participant.
        |A Dar is a collection of LF-packages, the native binary representation of Daml smart contracts.
        |In order to use Daml templates on a participant, the Dar must first be uploaded and then
        |vetted by the participant. Vetting will ensure that other participants can check whether they
        |can actually send a transaction referring to a particular Daml package and participant.
        |Vetting is done by registering a VettedPackages topology transaction with the topology manager.
        |By default, vetting happens automatically and this command waits for
        |the vetting transaction to be successfully registered on all connected domains.
        |This is the safe default setting minimizing race conditions.
        |
        |If vetAllPackages is true (default), the packages will all be vetted on all domains the participant is registered.
        |If synchronizeVetting is true (default), then the command will block until the participant has observed the vetting transactions to be registered with the domain.
        |
        |Note that synchronize vetting might block on permissioned domains that do not just allow participants to update the topology state.
        |In such cases, synchronizeVetting should be turned off.
        |Synchronize vetting can be invoked manually using $participant.package.synchronize_vettings()
        |""")
    def upload(
        path: String,
        vetAllPackages: Boolean = true,
        synchronizeVetting: Boolean = true,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): String = {
      val res = consoleEnvironment.runE {
        for {
          hash <- ParticipantCommands.dars
            .upload(runner, path, vetAllPackages, synchronizeVetting, logger)
            .toEither
        } yield hash
      }
      if (synchronizeVetting && vetAllPackages) {
        packages.synchronize_vetting()
        synchronize.foreach { timeout =>
          ConsoleMacros.utils.synchronize_topology(Some(timeout))(consoleEnvironment)
        }
      }
      res
    }

    @Help.Summary("Downloads the DAR file with the given hash to the given directory")
    def download(darHash: String, directory: String): Unit = {
      val _ = consoleEnvironment.run {
        adminCommand(
          ParticipantAdminCommands.Package.GetDar(Some(darHash), Some(directory), logger)
        )
      }
    }

    @Help.Summary("Change DAR vetting status")
    @Help.Group("Vetting")
    object vetting extends Helpful {
      @Help.Summary(
        "Vet all packages contained in the DAR archive identified by the provided DAR hash."
      )
      def enable(darHash: String, synchronize: Boolean = true): Unit =
        check(FeatureFlag.Preview)(consoleEnvironment.run {
          adminCommand(ParticipantAdminCommands.Package.VetDar(darHash, synchronize))
        })

      @Help.Summary("""Revoke vetting for all packages contained in the DAR archive
          |identified by the provided DAR hash.""")
      @Help.Description("""This command succeeds if the vetting command used to vet the DAR's packages
          |was symmetric and resulted in a single vetting topology transaction for all the packages in the DAR.
          |This command is potentially dangerous and misuse
          |can lead the participant to fail in processing transactions""")
      def disable(darHash: String): Unit =
        check(FeatureFlag.Preview)(consoleEnvironment.run {
          adminCommand(ParticipantAdminCommands.Package.UnvetDar(darHash))
        })
    }

  }

  @Help.Summary("Manage raw Daml-LF packages")
  @Help.Group("Package Management")
  object packages extends Helpful {

    @Help.Summary("List packages stored on the participant")
    @Help.Description("""Supported arguments:
        limit - Limit on the number of packages returned (defaults to canton.parameters.console.default-limit)
        """)
    def list(limit: PositiveInt = defaultLimit): Seq[v30.PackageDescription] =
      consoleEnvironment.run {
        adminCommand(ParticipantAdminCommands.Package.List(limit))
      }

    @Help.Summary("List package contents")
    def list_contents(packageId: String): Seq[v30.ModuleDescription] = consoleEnvironment.run {
      adminCommand(ParticipantAdminCommands.Package.ListContents(packageId))
    }

    @Help.Summary("Find packages that contain a module with the given name")
    def find(
        moduleName: String,
        limitPackages: PositiveInt = defaultLimit,
    ): Seq[v30.PackageDescription] = consoleEnvironment.runE {
      val packageC = adminCommand(ParticipantAdminCommands.Package.List(limitPackages)).toEither
      val matchingC = packageC
        .flatMap { packages =>
          packages.traverse(x =>
            adminCommand(ParticipantAdminCommands.Package.ListContents(x.packageId)).toEither.map(
              r => (x, r)
            )
          )
        }
      matchingC.map(_.filter { case (_, content) =>
        content.map(_.name).contains(moduleName)
      }.map(_._1))
    }

    @Help.Summary(
      "Remove the package from Canton's package store.",
      FeatureFlag.Preview,
    )
    @Help.Description(
      """The standard operation of this command checks that a package is unused and unvetted, and if so
        |removes the package. The force flag can be used to disable the checks, but do not use the force flag unless
        |you're certain you know what you're doing. """
    )
    def remove(packageId: String, force: Boolean = false): Unit = {
      check(FeatureFlag.Preview)(consoleEnvironment.run {
        adminCommand(ParticipantAdminCommands.Package.RemovePackage(packageId, force))
      })
    }

    @Help.Summary(
      "Ensure that all vetting transactions issued by this participant have been observed by all configured participants"
    )
    @Help.Description("""Sometimes, when scripting tests and demos, a dar or package is uploaded and we need to ensure
        |that commands are only submitted once the package vetting has been observed by some other connected participant
        |known to the console. This command can be used in such cases.""")
    // Also checks that the packages stored by Canton are the same as by the ledger api server.
    // However, this check is bypassed if there are 1000 or more packages at the ledger api server.
    def synchronize_vetting(
        timeout: config.NonNegativeDuration = consoleEnvironment.commandTimeouts.bounded
    ): Unit = {

      // ensure that the ledger api server has seen all packages
      ConsoleMacros.utils.retry_until_true(timeout)(
        {
          val canton = packages.list().map(_.packageId).toSet
          val maxPackages = PositiveInt.tryCreate(1000)
          val lApi = consoleEnvironment
            .run {
              ledgerApiCommand(
                LedgerApiCommands.PackageService.ListKnownPackages(maxPackages)
              )
            }
            .map(_.packageId)
            .toSet
          // don't synchronise anymore in a big production system (as we only need this truly for testing)
          (lApi.size >= maxPackages.value) || (canton -- lApi).isEmpty
        },
        show"Participant $id ledger Api server has still a different set of packages than the sync server",
      )

      waitPackagesVetted(timeout)
    }
  }

  @Help.Summary("Manage domain connections")
  @Help.Group("Domains")
  object domains extends Helpful {

    @Help.Summary("Returns the id of the given domain alias")
    def id_of(domainAlias: DomainAlias): DomainId = {
      consoleEnvironment.run {
        adminCommand(ParticipantAdminCommands.DomainConnectivity.GetDomainId(domainAlias))
      }
    }

    @Help.Summary(
      "Test whether a participant is connected to and permissioned on a domain."
    )
    @Help.Description(
      """Yields false, if the domain is not connected or not healthy.
        |Yields false, if the domain is configured in the Canton configuration and
        |the participant is not active from the perspective of the domain."""
    )
    def active(domainAlias: DomainAlias): Boolean = {
      list_connected().exists(r => {
        // TODO(#14053): Filter out participants that are not permissioned on the domain. The TODO is because the daml 2.x
        //  also asks the domain whether the participant is permissioned, i.e. do we need to for a ParticipantDomainPermissionX?
        r.domainAlias == domainAlias &&
        r.healthy &&
        participantIsActiveOnDomain(r.domainId, id)
      })
    }

    @Help.Summary(
      "Test whether a participant is connected to a domain"
    )
    def is_connected(domainId: DomainId): Boolean =
      list_connected().exists(_.domainId == domainId)

    @Help.Summary(
      "Macro to connect a participant to a locally configured domain given by reference"
    )
    @Help.Description("""
        The arguments are:
          domain - A local domain or sequencer reference
          alias - The name you will be using to refer to this domain. Can not be changed anymore.
          manualConnect - Whether this connection should be handled manually and also excluded from automatic re-connect.
          maxRetryDelayMillis - Maximal amount of time (in milliseconds) between two connection attempts.
          priority - The priority of the domain. The higher the more likely a domain will be used.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def connect_local(
        domain: SequencerNodeReference,
        alias: DomainAlias,
        manualConnect: Boolean = false,
        maxRetryDelayMillis: Option[Long] = None,
        priority: Int = 0,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Unit = {
      val config = ParticipantCommands.domains.reference_to_config(
        NonEmpty.mk(Seq, SequencerAlias.Default -> domain).toMap,
        alias,
        manualConnect,
        maxRetryDelayMillis.map(NonNegativeFiniteDuration.tryOfMillis),
        priority,
      )
      connectFromConfig(config, synchronize)
    }

    @Help.Summary(
      "Macro to register a locally configured domain given by reference"
    )
    @Help.Description("""
        The arguments are:
          domain - A local domain or sequencer reference
          alias - The name you will be using to refer to this domain. Can not be changed anymore.
          handshake only - If yes, only the handshake will be perfomed (no domain connection)
          maxRetryDelayMillis - Maximal amount of time (in milliseconds) between two connection attempts.
          priority - The priority of the domain. The higher the more likely a domain will be used.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def register(
        domain: SequencerNodeReference,
        alias: DomainAlias,
        handshakeOnly: Boolean = false,
        maxRetryDelayMillis: Option[Long] = None,
        priority: Int = 0,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Unit = {
      val config = ParticipantCommands.domains.reference_to_config(
        NonEmpty.mk(Seq, SequencerAlias.Default -> domain).toMap,
        alias,
        manualConnect = false,
        maxRetryDelayMillis.map(NonNegativeFiniteDuration.tryOfMillis),
        priority,
      )
      register_with_config(config, handshakeOnly = handshakeOnly, synchronize)
    }

    @Help.Summary(
      "Macro to register a locally configured domain given by reference"
    )
    @Help.Description("""
        The arguments are:
          config - Config for the domain connection
          handshake only - If yes, only the handshake will be perfomed (no domain connection)
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def register_with_config(
        config: DomainConnectionConfig,
        handshakeOnly: Boolean,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Unit = {
      val current = this.config(config.domain)
      // if the config is not found, then we register the domain
      if (current.isEmpty) {
        // register the domain configuration
        consoleEnvironment.run {
          ParticipantCommands.domains.register(runner, config, handshakeOnly = handshakeOnly)
        }
      }
      synchronize.foreach { timeout =>
        ConsoleMacros.utils.synchronize_topology(Some(timeout))(consoleEnvironment)
      }
    }

    def connect_local_bft(
        domain: NonEmpty[Map[SequencerAlias, SequencerNodeReference]],
        alias: DomainAlias,
        manualConnect: Boolean = false,
        maxRetryDelayMillis: Option[Long] = None,
        priority: Int = 0,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
        sequencerTrustThreshold: PositiveInt = PositiveInt.one,
        submissionRequestAmplification: PositiveInt = PositiveInt.one,
    ): Unit = {
      val config = ParticipantCommands.domains.reference_to_config(
        domain,
        alias,
        manualConnect,
        maxRetryDelayMillis.map(NonNegativeFiniteDuration.tryOfMillis),
        priority,
        sequencerTrustThreshold,
        submissionRequestAmplification,
      )
      connectFromConfig(config, synchronize)
    }

    @Help.Summary("Macro to connect a participant to a domain given by connection")
    @Help.Description("""This variant of connect expects a domain connection config.
        |Otherwise the behaviour is equivalent to the connect command with explicit
        |arguments. If the domain is already configured, the domain connection
        |will be attempted. If however the domain is offline, the command will fail.
        |Generally, this macro should only be used to setup a new domain. However, for
        |convenience, we support idempotent invocations where subsequent calls just ensure
        |that the participant reconnects to the domain.
        |""")
    def connect(
        config: DomainConnectionConfig
    ): Unit = {
      connectFromConfig(config, None)
    }

    @Help.Summary("Macro to connect a participant to a domain given by instance")
    @Help.Description("""This variant of connect expects an instance with a sequencer connection.
        |Otherwise the behaviour is equivalent to the connect command with explicit
        |arguments. If the domain is already configured, the domain connection
        |will be attempted. If however the domain is offline, the command will fail.
        |Generally, this macro should only be used to setup a new domain. However, for
        |convenience, we support idempotent invocations where subsequent calls just ensure
        |that the participant reconnects to the domain.
        |""")
    def connect(
        instance: SequencerNodeReference,
        domainAlias: DomainAlias,
    ): Unit =
      connect(
        DomainConnectionConfig(
          domainAlias,
          SequencerConnections.single(instance.sequencerConnection),
        )
      )

    private def connectFromConfig(
        config: DomainConnectionConfig,
        synchronize: Option[NonNegativeDuration],
    ): Unit = {
      val current = this.config(config.domain)
      if (current.isEmpty) {
        // architecture-handbook-entry-begin: OnboardParticipantConnect
        // register the domain configuration
        consoleEnvironment.run {
          ParticipantCommands.domains.register(runner, config, handshakeOnly = false)
        }
        if (!config.manualConnect) {
          reconnect(config.domain.unwrap, retry = false).discard
          // now update the domain settings to auto-connect
          modify(config.domain.unwrap, _.copy(manualConnect = false))
        }
        // architecture-handbook-entry-end: OnboardParticipantConnect
      } else if (!config.manualConnect) {
        reconnect(config.domain, retry = false).discard
        modify(config.domain.unwrap, _.copy(manualConnect = false))
      }
      synchronize.foreach { timeout =>
        ConsoleMacros.utils.synchronize_topology(Some(timeout))(consoleEnvironment)
      }
    }

    @Help.Summary("Macro to connect a participant to a domain given by connection")
    @Help.Description("""The connect macro performs a series of commands in order to connect this participant to a domain.
        |First, `register` will be invoked with the given arguments, but first registered
        |with manualConnect = true. If you already set manualConnect = true, then nothing else
        |will happen and you will have to do the remaining steps yourselves.
        |Finally, the command will invoke `reconnect` to startup the connection.
        |If the reconnect succeeded, the registered configuration will be updated
        |with manualStart = true. If anything fails, the domain will remain registered with `manualConnect = true` and
        |you will have to perform these steps manually.
        The arguments are:
          domainAlias - The name you will be using to refer to this domain. Can not be changed anymore.
          connection - The connection string to connect to this domain. I.e. https://url:port
          manualConnect - Whether this connection should be handled manually and also excluded from automatic re-connect.
          domainId - Optionally the domainId you expect to see on this domain.
          certificatesPath - Path to TLS certificate files to use as a trust anchor.
          priority - The priority of the domain. The higher the more likely a domain will be used.
          timeTrackerConfig - The configuration for the domain time tracker.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def connect(
        domainAlias: DomainAlias,
        connection: String,
        manualConnect: Boolean = false,
        domainId: Option[DomainId] = None,
        certificatesPath: String = "",
        priority: Int = 0,
        timeTrackerConfig: DomainTimeTrackerConfig = DomainTimeTrackerConfig(),
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): DomainConnectionConfig = {
      val config = ParticipantCommands.domains.to_config(
        domainAlias,
        connection,
        manualConnect,
        domainId,
        certificatesPath,
        priority,
        timeTrackerConfig = timeTrackerConfig,
      )
      connectFromConfig(config, synchronize)
      config
    }

    @Help.Summary(
      "Macro to connect a participant to a domain that supports connecting via many endpoints"
    )
    @Help.Description("""Domains can provide many endpoints to connect to for availability and performance benefits.
        This version of connect allows specifying multiple endpoints for a single domain connection:
           connect_multi("mydomain", Seq(sequencer1, sequencer2))
           or:
           connect_multi("mydomain", Seq("https://host1.mydomain.net", "https://host2.mydomain.net", "https://host3.mydomain.net"))

        To create a more advanced connection config use domains.toConfig with a single host,
        |then use config.addConnection to add additional connections before connecting:
           config = myparticipaint.domains.toConfig("mydomain", "https://host1.mydomain.net", ...otherArguments)
           config = config.addConnection("https://host2.mydomain.net", "https://host3.mydomain.net")
           myparticipant.domains.connect(config)

        The arguments are:
          domainAlias - The name you will be using to refer to this domain. Can not be changed anymore.
          connections - The sequencer connection definitions (can be an URL) to connect to this domain. I.e. https://url:port
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def connect_multi(
        domainAlias: DomainAlias,
        connections: Seq[SequencerConnection],
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): DomainConnectionConfig = {
      val sequencerConnection =
        SequencerConnection.merge(connections).getOrElse(sys.error("Invalid sequencer connection"))
      val sequencerConnections =
        SequencerConnections.single(sequencerConnection)
      val config = DomainConnectionConfig(
        domainAlias,
        sequencerConnections,
      )
      connectFromConfig(config, synchronize)
      config
    }

    @Help.Summary("Reconnect this participant to the given domain")
    @Help.Description("""Idempotent attempts to re-establish a connection to a certain domain.
        |If retry is set to false, the command will throw an exception if unsuccessful.
        |If retry is set to true, the command will terminate after the first attempt with the result,
        |but the server will keep on retrying to connect to the domain.
        |
        The arguments are:
          domainAlias - The name you will be using to refer to this domain. Can not be changed anymore.
          retry - Whether the reconnect should keep on retrying until it succeeded or abort noisly if the connection attempt fails.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def reconnect(
        domainAlias: DomainAlias,
        retry: Boolean = true,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Boolean = {
      val ret = consoleEnvironment.run {
        adminCommand(ParticipantAdminCommands.DomainConnectivity.ConnectDomain(domainAlias, retry))
      }
      if (ret) {
        synchronize.foreach { timeout =>
          ConsoleMacros.utils.synchronize_topology(Some(timeout))(consoleEnvironment)
        }
      }
      ret
    }

    @Help.Summary("Reconnect this participant to the given local domain")
    @Help.Description("""Idempotent attempts to re-establish a connection to the given local domain.
        |Same behaviour as generic reconnect.

        The arguments are:
          ref - The domain reference to connect to
          retry - Whether the reconnect should keep on retrying until it succeeded or abort noisly if the connection attempt fails.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def reconnect_local(
        ref: SequencerNodeReference
    ): Boolean = reconnect(ref.name)

    @Help.Summary("Reconnect this participant to the given local domain")
    @Help.Description("""Idempotent attempts to re-establish a connection to the given local domain.
        |Same behaviour as generic reconnect.

        The arguments are:
          domainAlias - The domain alias to connect to
          retry - Whether the reconnect should keep on retrying until it succeeded or abort noisly if the connection attempt fails.
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
        """)
    def reconnect_local(
        domainAlias: DomainAlias,
        retry: Boolean = true,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Boolean = reconnect(domainAlias, retry, synchronize)

    @Help.Summary("Reconnect this participant to all domains which are not marked as manual start")
    @Help.Description("""
      The arguments are:
          ignoreFailures - If set to true (default), we'll attempt to connect to all, ignoring any failure
          synchronize - A timeout duration indicating how long to wait for all topology changes to have been effected on all local nodes.
    """)
    def reconnect_all(
        ignoreFailures: Boolean = true,
        synchronize: Option[NonNegativeDuration] = Some(
          consoleEnvironment.commandTimeouts.bounded
        ),
    ): Unit = {
      consoleEnvironment.run {
        adminCommand(
          ParticipantAdminCommands.DomainConnectivity.ReconnectDomains(ignoreFailures)
        )
      }
      synchronize.foreach { timeout =>
        ConsoleMacros.utils.synchronize_topology(Some(timeout))(consoleEnvironment)
      }
    }

    @Help.Summary("Disconnect this participant from the given domain")
    def disconnect(domainAlias: DomainAlias): Unit = consoleEnvironment.run {
      adminCommand(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(domainAlias))
    }

    @Help.Summary("Disconnect this participant from all connected domains")
    def disconnect_all(): Unit = {
      list_connected().foreach { connected =>
        disconnect(connected.domainAlias)
      }
    }

    @Help.Summary("Disconnect this participant from the given local domain")
    def disconnect_local(domain: DomainAlias): Unit = consoleEnvironment.run {
      adminCommand(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(domain))
    }

    @Help.Summary("List the connected domains of this participant")
    def list_connected(): Seq[ListConnectedDomainsResult] = consoleEnvironment.run {
      adminCommand(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
    }

    @Help.Summary("List the configured domains of this participant")
    def list_registered(): Seq[(DomainConnectionConfig, Boolean)] = consoleEnvironment.run {
      adminCommand(ParticipantAdminCommands.DomainConnectivity.ListConfiguredDomains)
    }

    @Help.Summary("Returns true if a domain is registered using the given alias")
    def is_registered(domain: DomainAlias): Boolean =
      config(domain).nonEmpty

    @Help.Summary("Returns the current configuration of a given domain")
    def config(domain: DomainAlias): Option[DomainConnectionConfig] =
      list_registered().map(_._1).find(_.domain == domain)

    @Help.Summary("Modify existing domain connection")
    def modify(
        domain: DomainAlias,
        modifier: DomainConnectionConfig => DomainConnectionConfig,
    ): Unit = {
      consoleEnvironment.runE {
        for {
          configured <- adminCommand(
            ParticipantAdminCommands.DomainConnectivity.ListConfiguredDomains
          ).toEither
          cfg <- configured
            .map(_._1)
            .find(_.domain == domain)
            .toRight(s"No such domain ${domain} configured")
          newConfig = modifier(cfg)
          _ <-
            if (newConfig.domain == cfg.domain) Right(())
            else Left("We don't support modifying the domain alias of a DomainConnectionConfig.")
          _ <- adminCommand(
            ParticipantAdminCommands.DomainConnectivity.ModifyDomainConnection(modifier(cfg))
          ).toEither
        } yield ()
      }
    }

  }

  @Help.Summary("Composability related functionality", FeatureFlag.Preview)
  @Help.Group("Transfer")
  object transfer extends Helpful {

    @Help.Summary("Search the currently in-flight transfers", FeatureFlag.Testing)
    @Help.Description(
      "Returns all in-flight transfers with the given target domain that match the filters, but no more than the limit specifies."
    )
    def search(
        targetDomain: DomainAlias,
        filterSourceDomain: Option[DomainAlias],
        filterTimestamp: Option[Instant],
        filterSubmittingParty: Option[PartyId],
        limit: PositiveInt = defaultLimit,
    ): Seq[TransferSearchResult] =
      check(FeatureFlag.Preview)(consoleEnvironment.run {
        adminCommand(
          ParticipantAdminCommands.Transfer
            .TransferSearch(
              targetDomain,
              filterSourceDomain,
              filterTimestamp,
              filterSubmittingParty,
              limit.value,
            )
        )
      })
  }

  @Help.Summary("Functionality for managing resources")
  @Help.Group("Resource Management")
  object resources extends Helpful {

    @Help.Summary("Set resource limits for the participant.")
    @Help.Description(
      """While a resource limit is attained or exceeded, the participant will reject any additional submission with GRPC status ABORTED.
        |Most importantly, a submission will be rejected **before** it consumes a significant amount of resources.
        |
        |There are three kinds of limits: `maxDirtyRequests`,  `maxRate` and `maxBurstFactor`.
        |The number of dirty requests of a participant P covers (1) requests initiated by P as well as
        |(2) requests initiated by participants other than P that need to be validated by P.
        |Compared to the maximum rate, the maximum number of dirty requests reflects the load on the participant more accurately.
        |However, the maximum number of dirty requests alone does not protect the system from "bursts":
        |If an application submits a huge number of commands at once, the maximum number of dirty requests will likely
        |be exceeded, as the system is registering dirty requests only during validation and not already during
        |submission.
        |
        |The maximum rate is a hard limit on the rate of commands submitted to this participant through the ledger API.
        |As the rate of commands is checked and updated immediately after receiving a new command submission,
        |an application cannot exceed the maximum rate.
        |
        |The `maxBurstFactor` parameter (positive, default 0.5) allows to configure how permissive the rate limitation should be
        |with respect to bursts. The rate limiting will be enforced strictly after having observed `max_burst` * `max_rate` commands.
        |
        |For the sake of illustration, let's assume the configured rate limit is ``100 commands/s`` with a burst ratio of 0.5.
        |If an application submits 100 commands within a single second, waiting exactly 10 milliseconds between consecutive commands,
        |then the participant will accept all commands.
        |With a `maxBurstFactor` of 0.5, the participant will accept the first 50 commands and reject the remaining 50.
        |If the application then waits another 500 ms, it may submit another burst of 50 commands. If it waits 250 ms,
        |it may submit only a burst of 25 commands.
        |
        |Resource limits can only be changed, if the server runs Canton enterprise.
        |In the community edition, the server uses fixed limits that cannot be changed."""
    )
    def set_resource_limits(limits: ResourceLimits): Unit =
      consoleEnvironment.run { adminCommand(SetResourceLimits(limits)) }

    @Help.Summary("Get the resource limits of the participant.")
    def resource_limits(): ResourceLimits = consoleEnvironment.run {
      adminCommand(GetResourceLimits())
    }

  }
}

trait ParticipantHealthAdministrationCommon extends FeatureFlagFilter {
  this: HealthAdministrationCommon[ParticipantStatus] =>

  protected def runner: AdminCommandRunner

  // Single internal implementation so that `maybe_ping`
  // can be hidden behind the `testing` feature flag
  private def ping_internal(
      participantId: ParticipantId,
      timeout: NonNegativeDuration,
      domainId: Option[DomainId],
      workflowId: String,
      id: String,
  ): Either[String, Duration] =
    consoleEnvironment.run {
      runner.adminCommand(
        ParticipantAdminCommands.Ping
          .Ping(
            Set[String](participantId.adminParty.toLf),
            Set(),
            timeout,
            0,
            domainId,
            workflowId,
            id,
          )
      )
    }

  @Help.Summary(
    "Sends a ping to the target participant over the ledger. " +
      "Yields the duration in case of success and throws a RuntimeException in case of failure."
  )
  def ping(
      participantId: ParticipantId,
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ping,
      domainId: Option[DomainId] = None,
      workflowId: String = "",
      id: String = "",
  ): Duration = {
    val adminApiRes: Either[String, Duration] =
      ping_internal(participantId, timeout, domainId, workflowId, id)
    consoleEnvironment.runE(
      adminApiRes.leftMap { reason =>
        s"Unable to ping $participantId within ${LoggerUtil
            .roundDurationForHumans(timeout.duration)}: ${reason}"
      }
    )

  }

  @Help.Summary(
    "Sends a ping to the target participant over the ledger. Yields Some(duration) in case of success and None in case of failure.",
    FeatureFlag.Testing,
  )
  def maybe_ping(
      participantId: ParticipantId,
      timeout: NonNegativeDuration = consoleEnvironment.commandTimeouts.ping,
      domainId: Option[DomainId] = None,
      workflowId: String = "",
      id: String = "",
  ): Option[Duration] = check(FeatureFlag.Testing) {
    ping_internal(participantId, timeout, domainId, workflowId, id).toOption
  }
}

class ParticipantHealthAdministrationX(
    val runner: AdminCommandRunner,
    val consoleEnvironment: ConsoleEnvironment,
    override val loggerFactory: NamedLoggerFactory,
) extends HealthAdministrationX(
      runner,
      consoleEnvironment,
      ParticipantStatus.fromProtoV30,
    )
    with FeatureFlagFilter
    with ParticipantHealthAdministrationCommon
