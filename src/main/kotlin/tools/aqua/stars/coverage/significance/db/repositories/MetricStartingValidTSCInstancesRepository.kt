/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance.db.repositories

import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricStartingValidTSCInstancesEntry
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable

/** Repository for [MetricStartingValidTSCInstancesEntry]s. */
object MetricStartingValidTSCInstancesRepository {

  /**
   * Inserts a new [MetricStartingValidTSCInstancesEntry] into the database.
   *
   * @param entry Entry to insert.
   * @return Inserted entry.
   */
  fun insert(entry: MetricStartingValidTSCInstancesEntry): MetricStartingValidTSCInstancesEntry =
      transaction {
        require(entry.id == null) { "insert() expects entry.id == null." }

        val newId =
            MetricStartingValidTSCInstancesTable.insertAndGetId { row ->
                  row[run] = entry.runId
                  row[tsc] = entry.tscId
                  row[tscInstance] = entry.tscInstanceId
                  row[scenarioConfig] = entry.scenarioConfigId
                  row[createdAt] = entry.createdAt
                }
                .value

        getById(newId)
            ?: error("Inserted MetricStartingValidTSCInstancesEntry not found (id=$newId).")
      }

  /**
   * Batch inserts multiple [MetricStartingValidTSCInstancesEntry]s into the database.
   *
   * @param entries Entries to insert.
   */
  fun batchInsert(entries: List<MetricStartingValidTSCInstancesEntry>) = transaction {
    if (entries.isEmpty()) return@transaction

    MetricStartingValidTSCInstancesTable.batchInsert(entries) { e ->
      this[MetricStartingValidTSCInstancesTable.run] = e.runId
      this[MetricStartingValidTSCInstancesTable.tsc] = e.tscId
      this[MetricStartingValidTSCInstancesTable.tscInstance] = e.tscInstanceId
      this[MetricStartingValidTSCInstancesTable.scenarioConfig] = e.scenarioConfigId
      this[MetricStartingValidTSCInstancesTable.createdAt] = e.createdAt
    }
  }

  /**
   * Retrieves a [MetricStartingValidTSCInstancesEntry] by its ID.
   *
   * @param id ID of the entry to retrieve.
   */
  fun getById(id: UUID): MetricStartingValidTSCInstancesEntry? = transaction {
    MetricStartingValidTSCInstancesTable.selectAll()
        .where { MetricStartingValidTSCInstancesTable.id eq id }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves a [MetricStartingValidTSCInstancesEntry] by its primary key.
   *
   * @param runId ID of the evaluation run.
   * @param tscId ID of the TSC.
   * @param scenarioConfigId ID of the scenario starting configuration.
   */
  fun getByKey(
      runId: UUID,
      tscId: UUID,
      scenarioConfigId: UUID
  ): MetricStartingValidTSCInstancesEntry? = transaction {
    MetricStartingValidTSCInstancesTable.selectAll()
        .where {
          (MetricStartingValidTSCInstancesTable.run eq runId) and
              (MetricStartingValidTSCInstancesTable.tsc eq tscId) and
              (MetricStartingValidTSCInstancesTable.scenarioConfig eq scenarioConfigId)
        }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all [MetricStartingValidTSCInstancesEntry]s for a given evaluation run.
   *
   * @param runId ID of the evaluation run.
   * @return All entries for the given evaluation run.
   */
  fun getAllForRun(runId: UUID): List<MetricStartingValidTSCInstancesEntry> = transaction {
    MetricStartingValidTSCInstancesTable.selectAll()
        .where { MetricStartingValidTSCInstancesTable.run eq runId }
        .map { it.toEntry() }
  }

  /**
   * Retrieves all [MetricStartingValidTSCInstancesEntry]s for a given evaluation run and TSC.
   *
   * @param runId ID of the evaluation run.
   * @param tscId ID of the TSC.
   * @return All entries for the given evaluation run and TSC.
   */
  fun getAllForRunAndTsc(runId: UUID, tscId: UUID): List<MetricStartingValidTSCInstancesEntry> =
      transaction {
        MetricStartingValidTSCInstancesTable.selectAll()
            .where {
              (MetricStartingValidTSCInstancesTable.run eq runId) and
                  (MetricStartingValidTSCInstancesTable.tsc eq tscId)
            }
            .map { it.toEntry() }
      }

  /**
   * Converts a [ResultRow] to a [MetricStartingValidTSCInstancesEntry].
   *
   * @return Converted [MetricStartingValidTSCInstancesEntry].
   */
  private fun ResultRow.toEntry(): MetricStartingValidTSCInstancesEntry =
      MetricStartingValidTSCInstancesEntry(
          id = this[MetricStartingValidTSCInstancesTable.id].value,
          runId = this[MetricStartingValidTSCInstancesTable.run].value,
          tscId = this[MetricStartingValidTSCInstancesTable.tsc].value,
          tscInstanceId = this[MetricStartingValidTSCInstancesTable.tscInstance].value,
          scenarioConfigId = this[MetricStartingValidTSCInstancesTable.scenarioConfig].value,
          createdAt = this[MetricStartingValidTSCInstancesTable.createdAt],
      )
}
