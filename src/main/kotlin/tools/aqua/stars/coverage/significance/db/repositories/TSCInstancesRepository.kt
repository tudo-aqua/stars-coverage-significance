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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsertReturning
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCInstanceEntry
import tools.aqua.stars.coverage.significance.db.tables.TSCInstancesTable

/** Repository for [TSCInstanceEntry]s. */
object TSCInstancesRepository {

  /**
   * Returns the existing row with the same instanceJson, or null if none exists.
   *
   * @param instanceJson The instance JSON to look up.
   * @return The matching row, or null if none exists.
   */
  fun getByInstanceJson(instanceJson: String): TSCInstanceEntry? = transaction {
    TSCInstancesTable.selectAll()
        .where { TSCInstancesTable.instanceJson eq instanceJson }
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
   * Insert only if there is no row with the same instanceJson. If such a row exists, returns that
   * existing entry.
   *
   * Concurrency: If you add a UNIQUE index/constraint on instance_json (recommended), the try/catch
   * path will correctly handle concurrent inserts.
   */
  fun upsert(entry: TSCInstanceEntry): TSCInstanceEntry = transaction {
    require(entry.id == null) { "upsertByInstanceJson() expects entry.id == null." }

    val row =
        TSCInstancesTable.upsertReturning(keys = arrayOf(TSCInstancesTable.instanceJson)) { st ->
              st[TSCInstancesTable.tsc] = entry.tscId
              st[TSCInstancesTable.createdAt] = entry.createdAt
              st[TSCInstancesTable.instanceJson] = entry.instanceJson
            }
            .single()

    row.toEntry()
  }

  /**
   * Insert only if there is no row with the same instanceJson. If such a row exists, returns that
   * existing entry.
   *
   * @param tscId The TSC ID.
   * @param instanceHash The instance hash.
   * @param instanceJson The instance JSON.
   * @return The existing or newly inserted entry.
   */
  fun upsert(
      tscId: UUID,
      instanceHash: String,
      instanceJson: String,
  ): TSCInstanceEntry =
      upsert(
          TSCInstanceEntry(
              id = null,
              tscId = tscId,
              instanceJson = instanceJson,
          ))

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
}
