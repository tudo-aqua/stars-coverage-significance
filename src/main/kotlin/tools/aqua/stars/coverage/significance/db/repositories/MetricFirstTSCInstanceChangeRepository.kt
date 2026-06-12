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

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFirstTSCInstanceChangeEntry
import tools.aqua.stars.coverage.significance.db.tables.MetricFirstTSCInstanceChangeTable

/** Repository for [MetricFirstTSCInstanceChangeEntry]s. */
object MetricFirstTSCInstanceChangeRepository {

  /** Removes all entries from the database. */
  fun clearTable() = transaction { MetricFirstTSCInstanceChangeTable.deleteAll() }

  /**
   * Inserts a new [MetricFirstTSCInstanceChangeEntry] into the database.
   *
   * @param entry Entry to insert.
   * @return Inserted entry.
   */
  fun insert(entry: MetricFirstTSCInstanceChangeEntry): MetricFirstTSCInstanceChangeEntry =
      transaction {
        require(entry.id == null) { "insert() expects entry.id == null." }

        val newId =
            MetricFirstTSCInstanceChangeTable.insertAndGetId { row ->
                  row[run] = entry.runId
                  row[tsc] = entry.tscId
                  row[scenarioConfig] = entry.scenarioConfigId
                  row[firstChangeMillis] = entry.firstChangeMillis
                  row[createdAt] = entry.createdAt
                }
                .value

        getById(newId) ?: error("Inserted MetricFirstTSCInstanceChange not found (id=$newId).")
      }

  /**
   * Batch inserts multiple [MetricFirstTSCInstanceChangeEntry]s into the database.
   *
   * @param entries Entries to insert.
   */
  fun batchInsert(entries: List<MetricFirstTSCInstanceChangeEntry>) = transaction {
    if (entries.isEmpty()) return@transaction

    MetricFirstTSCInstanceChangeTable.batchInsert(entries) { e ->
      this[MetricFirstTSCInstanceChangeTable.run] = e.runId
      this[MetricFirstTSCInstanceChangeTable.tsc] = e.tscId
      this[MetricFirstTSCInstanceChangeTable.mutant] = e.mutantId
      this[MetricFirstTSCInstanceChangeTable.scenarioConfig] = e.scenarioConfigId
      this[MetricFirstTSCInstanceChangeTable.firstChangeMillis] = e.firstChangeMillis
      this[MetricFirstTSCInstanceChangeTable.createdAt] = e.createdAt
    }
  }

  /**
   * Retrieves a [MetricFirstTSCInstanceChangeEntry] by its ID.
   *
   * @param id ID of the entry to retrieve.
   */
  fun getById(id: Int): MetricFirstTSCInstanceChangeEntry? = transaction {
    MetricFirstTSCInstanceChangeTable.selectAll()
        .where { MetricFirstTSCInstanceChangeTable.id eq id }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves a [MetricFirstTSCInstanceChangeEntry] by its primary key.
   *
   * @param runId ID of the evaluation run.
   * @param tscId ID of the TSC.
   * @param scenarioConfigId ID of the scenario starting configuration.
   * @return The matching [MetricFirstTSCInstanceChangeEntry], or null if not found.
   */
  fun getByKey(
      runId: Int,
      tscId: Int,
      scenarioConfigId: Int
  ): MetricFirstTSCInstanceChangeEntry? = transaction {
    MetricFirstTSCInstanceChangeTable.selectAll()
        .where {
          (MetricFirstTSCInstanceChangeTable.run eq runId) and
              (MetricFirstTSCInstanceChangeTable.tsc eq tscId) and
              (MetricFirstTSCInstanceChangeTable.scenarioConfig eq scenarioConfigId)
        }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Converts a [ResultRow] to a [MetricFirstTSCInstanceChangeEntry].
   *
   * @return Converted [MetricFirstTSCInstanceChangeEntry].
   */
  private fun ResultRow.toEntry(): MetricFirstTSCInstanceChangeEntry =
      MetricFirstTSCInstanceChangeEntry(
          id = this[MetricFirstTSCInstanceChangeTable.id].value,
          runId = this[MetricFirstTSCInstanceChangeTable.run].value,
          tscId = this[MetricFirstTSCInstanceChangeTable.tsc].value,
          scenarioConfigId = this[MetricFirstTSCInstanceChangeTable.scenarioConfig].value,
          mutantId = this[MetricFirstTSCInstanceChangeTable.mutant].value,
          firstChangeMillis = this[MetricFirstTSCInstanceChangeTable.firstChangeMillis],
          createdAt = this[MetricFirstTSCInstanceChangeTable.createdAt],
      )
}
