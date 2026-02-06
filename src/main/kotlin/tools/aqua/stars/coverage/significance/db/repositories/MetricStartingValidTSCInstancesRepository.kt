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
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricStartingValidTSCInstancesEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable.createdAt
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable.scenarioConfig
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable.tsc
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable.tscInstance

/** Repository for [MetricStartingValidTSCInstancesEntry]s. */
object MetricStartingValidTSCInstancesRepository {

  /**
   * Returns a query containing all entries in the table.
   *
   * @return The query containing all entries.
   */
  fun getAll(): List<MetricStartingValidTSCInstancesEntry> = db {
    MetricStartingValidTSCInstancesTable.selectAll().map { it.toEntry() }
  }

  /**
   * Returns the number of entries in the table.
   *
   * @return The number of entries in the table.
   */
  fun count(): Long = transaction { MetricStartingValidTSCInstancesTable.selectAll().count() }

  /** Clears the table. */
  fun clearTable() = transaction { MetricStartingValidTSCInstancesTable.deleteAll() }

  /**
   * Inserts a list of [MetricStartingValidTSCInstancesEntry]s in a single batch.
   *
   * @param entries The entries to insert.
   */
  fun batchInsert(entries: List<MetricStartingValidTSCInstancesEntry>) {
    MetricStartingValidTSCInstancesTable.batchInsert(entries) { entry ->
      this[tsc] = entry.tscId
      this[tscInstance] = entry.tscInstanceId
      this[scenarioConfig] = entry.scenarioConfigId
      this[createdAt] = entry.createdAt
    }
  }

  /**
   * Inserts a new [MetricStartingValidTSCInstancesEntry] if it does not already exist, and returns
   * its ID.
   *
   * @param entry The entry to insert.
   * @return The ID of the inserted or existing entry.
   */
  fun insertIfMissingAndReturnId(entry: MetricStartingValidTSCInstancesEntry): UUID = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }

    MetricStartingValidTSCInstancesTable.insertIgnore { row ->
      row[tsc] = entry.tscId
      row[tscInstance] = entry.tscInstanceId
      row[scenarioConfig] = entry.scenarioConfigId
      row[createdAt] = entry.createdAt
    }

    MetricStartingValidTSCInstancesTable.select(MetricStartingValidTSCInstancesTable.id)
        .where {
          (MetricStartingValidTSCInstancesTable.tsc eq entry.tscId) and
              (MetricStartingValidTSCInstancesTable.tscInstance eq entry.tscInstanceId) and
              (MetricStartingValidTSCInstancesTable.scenarioConfig eq entry.scenarioConfigId)
        }
        .limit(1)
        .single()[MetricStartingValidTSCInstancesTable.id]
        .value
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
   * @param tscId ID of the TSC.
   * @param scenarioConfigId ID of the scenario starting configuration.
   */
  fun getByKey(tscId: UUID, scenarioConfigId: UUID): MetricStartingValidTSCInstancesEntry? =
      transaction {
        MetricStartingValidTSCInstancesTable.selectAll()
            .where {
              (MetricStartingValidTSCInstancesTable.tsc eq tscId) and
                  (MetricStartingValidTSCInstancesTable.scenarioConfig eq scenarioConfigId)
            }
            .limit(1)
            .firstOrNull()
            ?.toEntry()
      }

  /**
   * Retrieves all [MetricStartingValidTSCInstancesEntry]s for a given evaluation run and TSC.
   *
   * @param tscId ID of the TSC.
   * @return All entries for the given evaluation run and TSC.
   */
  fun getAllForTsc(tscId: UUID): List<MetricStartingValidTSCInstancesEntry> = transaction {
    MetricStartingValidTSCInstancesTable.selectAll()
        .where { (MetricStartingValidTSCInstancesTable.tsc eq tscId) }
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
          tscId = this[MetricStartingValidTSCInstancesTable.tsc].value,
          tscInstanceId = this[MetricStartingValidTSCInstancesTable.tscInstance].value,
          scenarioConfigId = this[MetricStartingValidTSCInstancesTable.scenarioConfig].value,
          createdAt = this[MetricStartingValidTSCInstancesTable.createdAt],
      )
}
