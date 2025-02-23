// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend.common

import com.digitalasset.canton.platform.store.backend.DbDto
import com.digitalasset.canton.platform.store.interning.StringInterning

import java.sql.Connection
import scala.reflect.ClassTag

private[backend] trait Schema[FROM] {
  def prepareData(in: Vector[FROM], stringInterning: StringInterning): Array[Array[Array[_]]]
  def executeUpdate(data: Array[Array[Array[_]]], connection: Connection): Unit
}

private[backend] object AppendOnlySchema {

  type Batch = Array[Array[Array[_]]]

  private[backend] trait FieldStrategy {
    def string[FROM](extractor: StringInterning => FROM => String): Field[FROM, String, _] =
      StringField(extractor)

    def stringOptional[FROM](
        extractor: StringInterning => FROM => Option[String]
    ): Field[FROM, Option[String], _] =
      StringOptional(extractor)

    def stringArray[FROM](
        extractor: StringInterning => FROM => Iterable[String]
    ): Field[FROM, Iterable[String], _] =
      StringArray(extractor)

    def intArray[FROM](
        extractor: StringInterning => FROM => Iterable[Int]
    ): Field[FROM, Iterable[Int], _] =
      IntArray(extractor)

    def intArrayOptional[FROM](
        extractor: StringInterning => FROM => Option[Iterable[Int]]
    ): Field[FROM, Option[Iterable[Int]], _] =
      IntArrayOptional(extractor)

    def bytea[FROM](
        extractor: StringInterning => FROM => Array[Byte]
    ): Field[FROM, Array[Byte], _] =
      Bytea(extractor)

    def byteaOptional[FROM](
        extractor: StringInterning => FROM => Option[Array[Byte]]
    ): Field[FROM, Option[Array[Byte]], _] =
      ByteaOptional(extractor)

    def bigint[FROM](extractor: StringInterning => FROM => Long): Field[FROM, Long, _] =
      Bigint(extractor)

    def bigintOptional[FROM](
        extractor: StringInterning => FROM => Option[Long]
    ): Field[FROM, Option[Long], _] =
      BigintOptional(extractor)

    def smallintOptional[FROM](
        extractor: StringInterning => FROM => Option[Int]
    ): Field[FROM, Option[Int], _] =
      SmallintOptional(extractor)

    def int[FROM](extractor: StringInterning => FROM => Int): Field[FROM, Int, _] =
      Integer(extractor)

    def intOptional[FROM](
        extractor: StringInterning => FROM => Option[Int]
    ): Field[FROM, Option[Int], _] =
      IntOptional(extractor)

    def booleanOptional[FROM](
        extractor: StringInterning => FROM => Option[Boolean]
    ): Field[FROM, Option[Boolean], _] =
      BooleanOptional(extractor)

    def insert[FROM](tableName: String)(fields: (String, Field[FROM, _, _])*): Table[FROM]
    def idempotentInsert[FROM](tableName: String, keyFieldIndex: Int, ordering: Ordering[FROM])(
        fields: (String, Field[FROM, _, _])*
    ): Table[FROM]
  }

  def apply(fieldStrategy: FieldStrategy): Schema[DbDto] = {
    val eventsCreate: Table[DbDto.EventCreate] =
      fieldStrategy.insert("lapi_events_create")(
        "event_offset" -> fieldStrategy.string(_ => _.event_offset),
        "transaction_id" -> fieldStrategy.string(_ => _.transaction_id),
        "ledger_effective_time" -> fieldStrategy.bigint(_ => _.ledger_effective_time),
        "command_id" -> fieldStrategy.stringOptional(_ => _.command_id),
        "workflow_id" -> fieldStrategy.stringOptional(_ => _.workflow_id),
        "application_id" -> fieldStrategy.stringOptional(_ => _.application_id),
        "submitters" -> fieldStrategy.intArrayOptional(stringInterning =>
          _.submitters.map(_.map(stringInterning.party.unsafe.internalize))
        ),
        "node_index" -> fieldStrategy.int(_ => _.node_index),
        "event_id" -> fieldStrategy.string(_ => _.event_id),
        "contract_id" -> fieldStrategy.string(_ => _.contract_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.templateId.unsafe.internalize(dbDto.template_id)
        ),
        "package_name" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.packageName.unsafe.internalize(dbDto.package_name)
        ),
        "flat_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.flat_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "tree_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.tree_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "create_argument" -> fieldStrategy.bytea(_ => _.create_argument),
        "create_signatories" -> fieldStrategy.intArray(stringInterning =>
          _.create_signatories.map(stringInterning.party.unsafe.internalize)
        ),
        "create_observers" -> fieldStrategy.intArray(stringInterning =>
          _.create_observers.map(stringInterning.party.unsafe.internalize)
        ),
        "create_key_value" -> fieldStrategy.byteaOptional(_ => _.create_key_value),
        "create_key_maintainers" -> fieldStrategy.intArrayOptional(stringInterning =>
          _.create_key_maintainers.map(_.map(stringInterning.party.unsafe.internalize))
        ),
        "create_key_hash" -> fieldStrategy.stringOptional(_ => _.create_key_hash),
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "create_argument_compression" -> fieldStrategy.smallintOptional(_ =>
          _.create_argument_compression
        ),
        "create_key_value_compression" -> fieldStrategy.smallintOptional(_ =>
          _.create_key_value_compression
        ),
        "driver_metadata" -> fieldStrategy.byteaOptional(_ => _.driver_metadata),
        "domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.domain_id)
        ),
        "trace_context" -> fieldStrategy.bytea(_ => _.trace_context),
        "record_time" -> fieldStrategy.bigint(_ => _.record_time),
      )

    val exerciseFields: Vector[(String, Field[DbDto.EventExercise, _, _])] =
      Vector[(String, Field[DbDto.EventExercise, _, _])](
        "event_id" -> fieldStrategy.string(_ => _.event_id),
        "event_offset" -> fieldStrategy.string(_ => _.event_offset),
        "contract_id" -> fieldStrategy.string(_ => _.contract_id),
        "transaction_id" -> fieldStrategy.string(_ => _.transaction_id),
        "ledger_effective_time" -> fieldStrategy.bigint(_ => _.ledger_effective_time),
        "node_index" -> fieldStrategy.int(_ => _.node_index),
        "command_id" -> fieldStrategy.stringOptional(_ => _.command_id),
        "workflow_id" -> fieldStrategy.stringOptional(_ => _.workflow_id),
        "application_id" -> fieldStrategy.stringOptional(_ => _.application_id),
        "submitters" -> fieldStrategy.intArrayOptional(stringInterning =>
          _.submitters.map(_.map(stringInterning.party.unsafe.internalize))
        ),
        "create_key_value" -> fieldStrategy.byteaOptional(_ => _.create_key_value),
        "exercise_choice" -> fieldStrategy.string(_ => _.exercise_choice),
        "exercise_argument" -> fieldStrategy.bytea(_ => _.exercise_argument),
        "exercise_result" -> fieldStrategy.byteaOptional(_ => _.exercise_result),
        "exercise_actors" -> fieldStrategy.intArray(stringInterning =>
          _.exercise_actors.map(stringInterning.party.unsafe.internalize)
        ),
        "exercise_child_event_ids" -> fieldStrategy.stringArray(_ => _.exercise_child_event_ids),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.templateId.unsafe.internalize(dbDto.template_id)
        ),
        "flat_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.flat_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "tree_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.tree_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "create_key_value_compression" -> fieldStrategy.smallintOptional(_ =>
          _.create_key_value_compression
        ),
        "exercise_argument_compression" -> fieldStrategy.smallintOptional(_ =>
          _.exercise_argument_compression
        ),
        "exercise_result_compression" -> fieldStrategy.smallintOptional(_ =>
          _.exercise_result_compression
        ),
        "domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.domain_id)
        ),
        "trace_context" -> fieldStrategy.bytea(_ => _.trace_context),
        "record_time" -> fieldStrategy.bigint(_ => _.record_time),
      )

    val eventsUnassign: Table[DbDto.EventUnassign] =
      fieldStrategy.insert("lapi_events_unassign")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "event_offset" -> fieldStrategy.string(_ => _.event_offset),
        "update_id" -> fieldStrategy.string(_ => _.update_id),
        "workflow_id" -> fieldStrategy.stringOptional(_ => _.workflow_id),
        "command_id" -> fieldStrategy.stringOptional(_ => _.command_id),
        "submitter" -> fieldStrategy.intOptional(stringInterning =>
          _.submitter.map(stringInterning.party.unsafe.internalize)
        ),
        "contract_id" -> fieldStrategy.string(_ => _.contract_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.templateId.unsafe.internalize(dbDto.template_id)
        ),
        "flat_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.flat_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "source_domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.source_domain_id)
        ),
        "target_domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.target_domain_id)
        ),
        "unassign_id" -> fieldStrategy.string(_ => _.unassign_id),
        "reassignment_counter" -> fieldStrategy.bigint(_ => _.reassignment_counter),
        "assignment_exclusivity" -> fieldStrategy.bigintOptional(_ => _.assignment_exclusivity),
        "trace_context" -> fieldStrategy.bytea(_ => _.trace_context),
        "record_time" -> fieldStrategy.bigint(_ => _.record_time),
      )

    val eventsAssign: Table[DbDto.EventAssign] =
      fieldStrategy.insert("lapi_events_assign")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "event_offset" -> fieldStrategy.string(_ => _.event_offset),
        "update_id" -> fieldStrategy.string(_ => _.update_id),
        "workflow_id" -> fieldStrategy.stringOptional(_ => _.workflow_id),
        "command_id" -> fieldStrategy.stringOptional(_ => _.command_id),
        "submitter" -> fieldStrategy.intOptional(stringInterning =>
          _.submitter.map(stringInterning.party.unsafe.internalize)
        ),
        "contract_id" -> fieldStrategy.string(_ => _.contract_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.templateId.unsafe.internalize(dbDto.template_id)
        ),
        "package_name" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.packageName.unsafe.internalize(dbDto.package_name)
        ),
        "flat_event_witnesses" -> fieldStrategy.intArray(stringInterning =>
          _.flat_event_witnesses.map(stringInterning.party.unsafe.internalize)
        ),
        "source_domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.source_domain_id)
        ),
        "target_domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.target_domain_id)
        ),
        "unassign_id" -> fieldStrategy.string(_ => _.unassign_id),
        "reassignment_counter" -> fieldStrategy.bigint(_ => _.reassignment_counter),
        "create_argument" -> fieldStrategy.bytea(_ => _.create_argument),
        "create_signatories" -> fieldStrategy.intArray(stringInterning =>
          _.create_signatories.map(stringInterning.party.unsafe.internalize)
        ),
        "create_observers" -> fieldStrategy.intArray(stringInterning =>
          _.create_observers.map(stringInterning.party.unsafe.internalize)
        ),
        "create_key_value" -> fieldStrategy.byteaOptional(_ => _.create_key_value),
        "create_key_maintainers" -> fieldStrategy.intArrayOptional(stringInterning =>
          _.create_key_maintainers.map(_.map(stringInterning.party.unsafe.internalize))
        ),
        "create_key_hash" -> fieldStrategy.stringOptional(_ => _.create_key_hash),
        "create_argument_compression" -> fieldStrategy.smallintOptional(_ =>
          _.create_argument_compression
        ),
        "create_key_value_compression" -> fieldStrategy.smallintOptional(_ =>
          _.create_key_value_compression
        ),
        "ledger_effective_time" -> fieldStrategy.bigint(_ => _.ledger_effective_time),
        "driver_metadata" -> fieldStrategy.bytea(_ => _.driver_metadata),
        "trace_context" -> fieldStrategy.bytea(_ => _.trace_context),
        "record_time" -> fieldStrategy.bigint(_ => _.record_time),
      )

    val eventsConsumingExercise: Table[DbDto.EventExercise] =
      fieldStrategy.insert("lapi_events_consuming_exercise")(exerciseFields*)

    val eventsNonConsumingExercise: Table[DbDto.EventExercise] =
      fieldStrategy.insert("lapi_events_non_consuming_exercise")(exerciseFields*)

    val configurationEntries: Table[DbDto.ConfigurationEntry] =
      fieldStrategy.insert("lapi_configuration_entries")(
        "ledger_offset" -> fieldStrategy.string(_ => _.ledger_offset),
        "recorded_at" -> fieldStrategy.bigint(_ => _.recorded_at),
        "submission_id" -> fieldStrategy.string(_ => _.submission_id),
        "typ" -> fieldStrategy.string(_ => _.typ),
        "configuration" -> fieldStrategy.bytea(_ => _.configuration),
        "rejection_reason" -> fieldStrategy.stringOptional(_ => _.rejection_reason),
      )

    val packageEntries: Table[DbDto.PackageEntry] =
      fieldStrategy.insert("lapi_package_entries")(
        "ledger_offset" -> fieldStrategy.string(_ => _.ledger_offset),
        "recorded_at" -> fieldStrategy.bigint(_ => _.recorded_at),
        "submission_id" -> fieldStrategy.stringOptional(_ => _.submission_id),
        "typ" -> fieldStrategy.string(_ => _.typ),
        "rejection_reason" -> fieldStrategy.stringOptional(_ => _.rejection_reason),
      )

    val packages: Table[DbDto.Package] =
      fieldStrategy.idempotentInsert(
        tableName = "lapi_packages",
        keyFieldIndex = 0,
        ordering = Ordering.by[DbDto.Package, String](_.package_id),
      )(
        "package_id" -> fieldStrategy.string(_ => _.package_id),
        "upload_id" -> fieldStrategy.string(_ => _.upload_id),
        "source_description" -> fieldStrategy.stringOptional(_ => _.source_description),
        "package_size" -> fieldStrategy.bigint(_ => _.package_size),
        "known_since" -> fieldStrategy.bigint(_ => _.known_since),
        "ledger_offset" -> fieldStrategy.string(_ => _.ledger_offset),
        "package" -> fieldStrategy.bytea(_ => _._package),
      )

    val partyEntries: Table[DbDto.PartyEntry] =
      fieldStrategy.insert("lapi_party_entries")(
        "ledger_offset" -> fieldStrategy.string(_ => _.ledger_offset),
        "recorded_at" -> fieldStrategy.bigint(_ => _.recorded_at),
        "submission_id" -> fieldStrategy.stringOptional(_ => _.submission_id),
        "party" -> fieldStrategy.stringOptional(_ => _.party),
        "display_name" -> fieldStrategy.stringOptional(_ => _.display_name),
        "typ" -> fieldStrategy.string(_ => _.typ),
        "rejection_reason" -> fieldStrategy.stringOptional(_ => _.rejection_reason),
        "is_local" -> fieldStrategy.booleanOptional(_ => _.is_local),
        "party_id" -> fieldStrategy.intOptional(stringInterning =>
          _.party.map(stringInterning.party.unsafe.internalize)
        ),
      )

    val commandCompletions: Table[DbDto.CommandCompletion] =
      fieldStrategy.insert("lapi_command_completions")(
        "completion_offset" -> fieldStrategy.string(_ => _.completion_offset),
        "record_time" -> fieldStrategy.bigint(_ => _.record_time),
        "application_id" -> fieldStrategy.string(_ => _.application_id),
        "submitters" -> fieldStrategy.intArray(stringInterning =>
          _.submitters.map(stringInterning.party.unsafe.internalize)
        ),
        "command_id" -> fieldStrategy.string(_ => _.command_id),
        "transaction_id" -> fieldStrategy.stringOptional(_ => _.transaction_id),
        "rejection_status_code" -> fieldStrategy.intOptional(_ => _.rejection_status_code),
        "rejection_status_message" -> fieldStrategy.stringOptional(_ => _.rejection_status_message),
        "rejection_status_details" -> fieldStrategy.byteaOptional(_ => _.rejection_status_details),
        "submission_id" -> fieldStrategy.stringOptional(_ => _.submission_id),
        "deduplication_offset" -> fieldStrategy.stringOptional(_ => _.deduplication_offset),
        "deduplication_duration_seconds" -> fieldStrategy.bigintOptional(_ =>
          _.deduplication_duration_seconds
        ),
        "deduplication_duration_nanos" -> fieldStrategy.intOptional(_ =>
          _.deduplication_duration_nanos
        ),
        "deduplication_start" -> fieldStrategy.bigintOptional(_ => _.deduplication_start),
        "domain_id" -> fieldStrategy.int(stringInterning =>
          dbDto => stringInterning.domainId.unsafe.internalize(dbDto.domain_id)
        ),
        "trace_context" -> fieldStrategy.bytea(_ => _.trace_context),
      )

    val stringInterningTable: Table[DbDto.StringInterningDto] =
      fieldStrategy.insert("lapi_string_interning")(
        "internal_id" -> fieldStrategy.int(_ => _.internalId),
        "external_string" -> fieldStrategy.string(_ => _.externalString),
      )

    val idFilterCreateStakeholderTable: Table[DbDto.IdFilterCreateStakeholder] =
      fieldStrategy.insert("lapi_pe_create_id_filter_stakeholder")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.templateId.unsafe.internalize(dto.template_id)
        ),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterCreateNonStakeholderInformeeTable
        : Table[DbDto.IdFilterCreateNonStakeholderInformee] =
      fieldStrategy.insert("lapi_pe_create_id_filter_non_stakeholder_informee")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterConsumingStakeholderTable: Table[DbDto.IdFilterConsumingStakeholder] =
      fieldStrategy.insert("lapi_pe_consuming_id_filter_stakeholder")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.templateId.unsafe.internalize(dto.template_id)
        ),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterConsumingNonStakeholderInformeeTable
        : Table[DbDto.IdFilterConsumingNonStakeholderInformee] =
      fieldStrategy.insert("lapi_pe_consuming_id_filter_non_stakeholder_informee")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterNonConsumingInformeeTable: Table[DbDto.IdFilterNonConsumingInformee] =
      fieldStrategy.insert("lapi_pe_non_consuming_id_filter_informee")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterUnassignStakeholderTable: Table[DbDto.IdFilterUnassignStakeholder] =
      fieldStrategy.insert("lapi_pe_unassign_id_filter_stakeholder")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.templateId.unsafe.internalize(dto.template_id)
        ),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val idFilterAssignStakeholderTable: Table[DbDto.IdFilterAssignStakeholder] =
      fieldStrategy.insert("lapi_pe_assign_id_filter_stakeholder")(
        "event_sequential_id" -> fieldStrategy.bigint(_ => _.event_sequential_id),
        "template_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.templateId.unsafe.internalize(dto.template_id)
        ),
        "party_id" -> fieldStrategy.int(stringInterning =>
          dto => stringInterning.party.unsafe.internalize(dto.party_id)
        ),
      )

    val transactionMeta: Table[DbDto.TransactionMeta] =
      fieldStrategy.insert("lapi_transaction_meta")(
        "transaction_id" -> fieldStrategy.string(_ => _.transaction_id),
        "event_offset" -> fieldStrategy.string(_ => _.event_offset),
        "event_sequential_id_first" -> fieldStrategy.bigint(_ => _.event_sequential_id_first),
        "event_sequential_id_last" -> fieldStrategy.bigint(_ => _.event_sequential_id_last),
      )

    val transactionMetering: Table[DbDto.TransactionMetering] =
      fieldStrategy.insert("lapi_transaction_metering")(
        fields = "application_id" -> fieldStrategy.string(_ => _.application_id),
        "action_count" -> fieldStrategy.int(_ => _.action_count),
        "metering_timestamp" -> fieldStrategy.bigint(_ => _.metering_timestamp),
        "ledger_offset" -> fieldStrategy.string(_ => _.ledger_offset),
      )

    val executes: Seq[Array[Array[_]] => Connection => Unit] = List(
      eventsCreate.executeUpdate,
      eventsConsumingExercise.executeUpdate,
      eventsNonConsumingExercise.executeUpdate,
      eventsUnassign.executeUpdate,
      eventsAssign.executeUpdate,
      configurationEntries.executeUpdate,
      packageEntries.executeUpdate,
      packages.executeUpdate,
      partyEntries.executeUpdate,
      commandCompletions.executeUpdate,
      stringInterningTable.executeUpdate,
      idFilterCreateStakeholderTable.executeUpdate,
      idFilterCreateNonStakeholderInformeeTable.executeUpdate,
      idFilterConsumingStakeholderTable.executeUpdate,
      idFilterConsumingNonStakeholderInformeeTable.executeUpdate,
      idFilterNonConsumingInformeeTable.executeUpdate,
      idFilterUnassignStakeholderTable.executeUpdate,
      idFilterAssignStakeholderTable.executeUpdate,
      transactionMeta.executeUpdate,
      transactionMetering.executeUpdate,
    )

    new Schema[DbDto] {
      override def prepareData(
          in: Vector[DbDto],
          stringInterning: StringInterning,
      ): Array[Array[Array[_]]] = {
        def collectWithFilter[T <: DbDto: ClassTag](filter: T => Boolean): Vector[T] =
          in.collect { case dbDto: T if filter(dbDto) => dbDto }
        def collect[T <: DbDto: ClassTag]: Vector[T] = collectWithFilter[T](_ => true)
        import DbDto.*
        Array(
          eventsCreate.prepareData(collect[EventCreate], stringInterning),
          eventsConsumingExercise
            .prepareData(collectWithFilter[EventExercise](_.consuming), stringInterning),
          eventsNonConsumingExercise
            .prepareData(collectWithFilter[EventExercise](!_.consuming), stringInterning),
          eventsUnassign.prepareData(collect[EventUnassign], stringInterning),
          eventsAssign.prepareData(collect[EventAssign], stringInterning),
          configurationEntries.prepareData(collect[ConfigurationEntry], stringInterning),
          packageEntries.prepareData(collect[PackageEntry], stringInterning),
          packages.prepareData(collect[Package], stringInterning),
          partyEntries.prepareData(collect[PartyEntry], stringInterning),
          commandCompletions.prepareData(collect[CommandCompletion], stringInterning),
          stringInterningTable.prepareData(collect[StringInterningDto], stringInterning),
          idFilterCreateStakeholderTable
            .prepareData(collect[IdFilterCreateStakeholder], stringInterning),
          idFilterCreateNonStakeholderInformeeTable
            .prepareData(collect[IdFilterCreateNonStakeholderInformee], stringInterning),
          idFilterConsumingStakeholderTable
            .prepareData(collect[IdFilterConsumingStakeholder], stringInterning),
          idFilterConsumingNonStakeholderInformeeTable
            .prepareData(collect[IdFilterConsumingNonStakeholderInformee], stringInterning),
          idFilterNonConsumingInformeeTable
            .prepareData(collect[IdFilterNonConsumingInformee], stringInterning),
          idFilterUnassignStakeholderTable
            .prepareData(collect[IdFilterUnassignStakeholder], stringInterning),
          idFilterAssignStakeholderTable
            .prepareData(collect[IdFilterAssignStakeholder], stringInterning),
          transactionMeta.prepareData(collect[TransactionMeta], stringInterning),
          transactionMetering.prepareData(collect[TransactionMetering], stringInterning),
        )
      }

      override def executeUpdate(data: Array[Array[Array[_]]], connection: Connection): Unit =
        executes.zip(data).foreach { case (execute, data) =>
          execute(data)(connection)
        }
    }
  }
}
