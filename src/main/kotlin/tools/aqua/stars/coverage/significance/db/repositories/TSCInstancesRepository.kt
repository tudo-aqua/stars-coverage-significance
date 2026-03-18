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
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.ScenarioIdAndJSON
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCInstanceEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.TSCInstancesTable

/** Repository for [TSCInstanceEntry]s. */
object TSCInstancesRepository {

  /**
   * Counts the number of TSC instances for a given TSC ID.
   *
   * @param tscId The ID of the TSC to count instances for.
   * @return The number of TSC instances associated with the given TSC ID.
   */
  fun countByTSC(tscId: UUID): Long = db {
    TSCInstancesTable.selectAll().where(TSCInstancesTable.tsc eq tscId).count()
  }

  /**
   * Returns the existing row with the same [instanceJson], or null if none exists.
   *
   * @param instanceJson The instance JSON to look up.
   * @param tscEntryId The ID of the TSC entry.
   * @return The matching row, or null if none exists.
   */
  fun getByInstanceJson(instanceJson: String, tscEntryId: UUID): TSCInstanceEntry? = transaction {
    TSCInstancesTable.selectAll()
        .where {
          (TSCInstancesTable.tsc eq tscEntryId) and (TSCInstancesTable.instanceJson eq instanceJson)
        }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves a [TSCInstanceEntry] by its primary key.
   *
   * @param id ID of the entry to retrieve.
   * @return The matching entry, or null if not found.
   */
  fun getById(id: UUID): TSCInstanceEntry? = transaction {
    TSCInstancesTable.selectAll()
        .where { TSCInstancesTable.id eq id }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Inserts a new [entry] into the database if it does not already exist.
   *
   * @param entry Entry to insert.
   * @return The ID of the inserted entry.
   */
  fun insertIfAbsentReturnId(entry: TSCInstanceEntry): UUID = transaction {
    require(entry.id == null) { "insertIfAbsentReturnId() expects entry.id == null." }

    TSCInstancesTable.insertIgnore { st ->
      st[TSCInstancesTable.tsc] = entry.tscId
      st[TSCInstancesTable.createdAt] = entry.createdAt
      st[TSCInstancesTable.instanceJson] = entry.instanceJson
    }

    TSCInstancesTable.select(TSCInstancesTable.id)
        .where {
          (TSCInstancesTable.tsc eq entry.tscId) and
              (TSCInstancesTable.instanceJson eq entry.instanceJson)
        }
        .limit(1)
        .single()[TSCInstancesTable.id]
        .value
  }

  /**
   * Converts a [ResultRow] to a [TSCInstanceEntry].
   *
   * @return Converted [TSCInstanceEntry].
   */
  private fun ResultRow.toEntry(): TSCInstanceEntry =
      TSCInstanceEntry(
          id = this[TSCInstancesTable.id].value,
          tscId = this[TSCInstancesTable.tsc].value,
          createdAt = this[TSCInstancesTable.createdAt],
          instanceJson = this[TSCInstancesTable.instanceJson],
      )

  fun getAllScenariosWithJSON(): List<ScenarioIdAndJSON> =
      TSCInstancesTable.select(TSCInstancesTable.id, TSCInstancesTable.instanceJson).map { row ->
        ScenarioIdAndJSON(
            scenarioId = row[TSCInstancesTable.id].value,
            scenarioJson = row[TSCInstancesTable.instanceJson])
      }
}
