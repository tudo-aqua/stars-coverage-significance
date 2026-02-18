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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsertReturning
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

/** Repository for managing [MetricFailedMonitorsEntry] in the [MetricFailedMonitorsTable]. */
object MetricFailedMonitorsRepository {

  /**
   * Retrieves a [MetricFailedMonitorsEntry] by its unique identifier.
   *
   * @param id Unique identifier of the metric entry.
   * @return The corresponding [MetricFailedMonitorsEntry] or null if not found.
   */
  fun getById(id: UUID): MetricFailedMonitorsEntry? = db {
    MetricFailedMonitorsTable.selectAll()
        .where { MetricFailedMonitorsTable.id eq id }
        .limit(1)
        .singleOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves a [MetricFailedMonitorsEntry] by its unique key: (run, tsc, scenario_config, mutant).
   *
   * @param runId Unique identifier of the evaluation run.
   * @param tscId Unique identifier of the TSC.
   * @param scenarioConfigId Unique identifier of the scenario starting configuration.
   * @param mutantId Unique identifier of the mutant.
   * @return The corresponding [MetricFailedMonitorsEntry] or null if not found.
   */
  fun getByKey(
      runId: UUID,
      tscId: UUID,
      scenarioConfigId: UUID,
      mutantId: UUID
  ): MetricFailedMonitorsEntry? = db {
    MetricFailedMonitorsTable.selectAll()
        .where {
          (MetricFailedMonitorsTable.run eq runId) and
              (MetricFailedMonitorsTable.tsc eq tscId) and
              (MetricFailedMonitorsTable.startingScenarioConfiguration eq scenarioConfigId) and
              (MetricFailedMonitorsTable.mutant eq mutantId)
        }
        .limit(1)
        .singleOrNull()
        ?.toEntry()
  }

  /**
   * Inserts multiple [MetricFailedMonitorsEntry] entries in a batch operation.
   *
   * @param entries List of [MetricFailedMonitorsEntry] to insert. Each entry's `id` must be null.
   */
  fun batchInsert(entries: List<MetricFailedMonitorsEntry>) = db {
    if (entries.isEmpty()) return@db

    MetricFailedMonitorsTable.batchInsert(entries) { e ->
      this[MetricFailedMonitorsTable.run] = e.runId
      this[MetricFailedMonitorsTable.tsc] = e.tscId
      this[MetricFailedMonitorsTable.mutant] = e.mutantId
      this[MetricFailedMonitorsTable.startingScenarioConfiguration] = e.scenarioConfigId

      this[MetricFailedMonitorsTable.monitorG0Failed] = e.monitorG0Failed
      this[MetricFailedMonitorsTable.monitorG1Failed] = e.monitorG1Failed
      this[MetricFailedMonitorsTable.monitorG2Failed] = e.monitorG2Failed
      this[MetricFailedMonitorsTable.monitorG22Failed] = e.monitorG22Failed
      this[MetricFailedMonitorsTable.monitorG3Failed] = e.monitorG3Failed
      this[MetricFailedMonitorsTable.monitorG4Failed] = e.monitorG4Failed
      this[MetricFailedMonitorsTable.monitorI1Failed] = e.monitorI1Failed
      this[MetricFailedMonitorsTable.monitorI2Failed] = e.monitorI2Failed
      this[MetricFailedMonitorsTable.monitorI3Failed] = e.monitorI3Failed
      this[MetricFailedMonitorsTable.monitorI4Failed] = e.monitorI4Failed

      this[MetricFailedMonitorsTable.createdAt] = e.createdAt
    }
  }

  /**
   * Inserts a new row and returns the canonical DB state (read-back).
   *
   * @param entry The [MetricFailedMonitorsEntry] to insert. Its `id` must be null.
   * @return The inserted [MetricFailedMonitorsEntry] with the generated `id`.
   */
  fun insert(entry: MetricFailedMonitorsEntry): MetricFailedMonitorsEntry = db {
    require(entry.id == null) { "insert() expects entry.id == null. Use upsert() otherwise." }

    val newId =
        MetricFailedMonitorsTable.insertAndGetId { row ->
              row[run] = entry.runId
              row[tsc] = entry.tscId
              row[startingScenarioConfiguration] = entry.scenarioConfigId
              row[mutant] = entry.mutantId

              row[monitorG0Failed] = entry.monitorG0Failed
              row[monitorG1Failed] = entry.monitorG1Failed
              row[monitorG2Failed] = entry.monitorG2Failed
              row[monitorG22Failed] = entry.monitorG22Failed
              row[monitorG3Failed] = entry.monitorG3Failed
              row[monitorG4Failed] = entry.monitorG4Failed
              row[monitorI1Failed] = entry.monitorI1Failed
              row[monitorI2Failed] = entry.monitorI2Failed
              row[monitorI3Failed] = entry.monitorI3Failed
              row[monitorI4Failed] = entry.monitorI4Failed

              row[createdAt] = entry.createdAt
            }
            .value

    getById(newId) ?: error("Inserted MetricFailedMonitorsEntry not found (id=$newId).")
  }

  /**
   * Inserts or updates a row and returns the canonical DB state (read-back).
   *
   * @param entry The [MetricFailedMonitorsEntry] to upsert. Its `id` must be null.
   * @return The upserted [MetricFailedMonitorsEntry] with the generated `id` (if inserted) or
   *   existing `id` (if updated).
   * @throws IllegalArgumentException if `entry.id` is not null.
   * @throws IllegalStateException if the upserted entry cannot be retrieved after the operation
   */
  fun upsert(entry: MetricFailedMonitorsEntry): MetricFailedMonitorsEntry = db {
    require(entry.id == null) { "upsert() expects entry.id == null." }

    val row =
        MetricFailedMonitorsTable.upsertReturning(
                keys =
                    arrayOf(
                        MetricFailedMonitorsTable.tsc,
                        MetricFailedMonitorsTable.run,
                        MetricFailedMonitorsTable.startingScenarioConfiguration,
                        MetricFailedMonitorsTable.mutant,
                    )) { st ->
                  st[run] = entry.runId
                  st[tsc] = entry.tscId
                  st[startingScenarioConfiguration] = entry.scenarioConfigId
                  st[mutant] = entry.mutantId

                  st[monitorG0Failed] = entry.monitorG0Failed
                  st[monitorG1Failed] = entry.monitorG1Failed
                  st[monitorG2Failed] = entry.monitorG2Failed
                  st[monitorG22Failed] = entry.monitorG22Failed
                  st[monitorG3Failed] = entry.monitorG3Failed
                  st[monitorG4Failed] = entry.monitorG4Failed
                  st[monitorI1Failed] = entry.monitorI1Failed
                  st[monitorI2Failed] = entry.monitorI2Failed
                  st[monitorI3Failed] = entry.monitorI3Failed
                  st[monitorI4Failed] = entry.monitorI4Failed

                  st[createdAt] = entry.createdAt
                }
            .single()

    row.toEntry()
  }

  /**
   * Deletes a [MetricFailedMonitorsEntry] by its unique identifier.
   *
   * @param id Unique identifier of the metric entry to delete.
   * @return The number of rows deleted (0 or 1).
   */
  fun deleteById(id: UUID): Int = db {
    MetricFailedMonitorsTable.deleteWhere { MetricFailedMonitorsTable.id eq id }
  }

  /**
   * Deletes all [MetricFailedMonitorsEntry] associated with a specific evaluation run.
   *
   * @param runId Unique identifier of the evaluation run.
   * @return The number of rows deleted.
   */
  fun deleteByRun(runId: UUID): Int = db {
    MetricFailedMonitorsTable.deleteWhere { MetricFailedMonitorsTable.run eq runId }
  }

  /**
   * Converts a [ResultRow] to a [MetricFailedMonitorsEntry].
   *
   * @return The corresponding [MetricFailedMonitorsEntry].
   */
  private fun ResultRow.toEntry(): MetricFailedMonitorsEntry =
      MetricFailedMonitorsEntry(
          id = this[MetricFailedMonitorsTable.id].value,
          runId = this[MetricFailedMonitorsTable.run].value,
          tscId = this[MetricFailedMonitorsTable.tsc].value,
          mutantId = this[MetricFailedMonitorsTable.mutant].value,
          scenarioConfigId = this[MetricFailedMonitorsTable.startingScenarioConfiguration].value,
          monitorG0Failed = this[MetricFailedMonitorsTable.monitorG0Failed],
          monitorG1Failed = this[MetricFailedMonitorsTable.monitorG1Failed],
          monitorG2Failed = this[MetricFailedMonitorsTable.monitorG2Failed],
          monitorG22Failed = this[MetricFailedMonitorsTable.monitorG22Failed],
          monitorG3Failed = this[MetricFailedMonitorsTable.monitorG3Failed],
          monitorG4Failed = this[MetricFailedMonitorsTable.monitorG4Failed],
          monitorI1Failed = this[MetricFailedMonitorsTable.monitorI1Failed],
          monitorI2Failed = this[MetricFailedMonitorsTable.monitorI2Failed],
          monitorI3Failed = this[MetricFailedMonitorsTable.monitorI3Failed],
          monitorI4Failed = this[MetricFailedMonitorsTable.monitorI4Failed],
          createdAt = this[MetricFailedMonitorsTable.createdAt],
      )
}
