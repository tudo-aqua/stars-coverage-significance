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
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable

/** Repository for [ScenarioStartingConfigurationEntry]s. */
object ScenarioStartingConfigurationRepository {

  /**
   * Returns the [ScenarioStartingConfigurationEntry] with the given [id] or null if not found.
   *
   * @param id The id of the entry to retrieve.
   * @return The entry with the given id or null if not found.
   */
  fun getById(id: UUID): ScenarioStartingConfigurationEntry? = transaction {
    ScenarioStartingConfigurationTable.selectAll()
        .where { ScenarioStartingConfigurationTable.id eq id }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Returns the [ScenarioStartingConfigurationEntry] with the given [scenarioFileName] or null if
   * not found.
   *
   * @param scenarioFileName The scenario file name of the entry to retrieve.
   * @return The entry with the given scenario file name or null if not found.
   */
  fun getByScenarioFileName(scenarioFileName: String): ScenarioStartingConfigurationEntry? =
      transaction {
        ScenarioStartingConfigurationTable.selectAll()
            .where { ScenarioStartingConfigurationTable.scenarioFileName eq scenarioFileName }
            .limit(1)
            .firstOrNull()
            ?.toEntry()
      }

  /**
   * Returns the [ScenarioStartingConfigurationEntry] with the given [hash] or null if not found.
   *
   * @param hash The hash of the entry to retrieve.
   * @return The entry with the given hash or null if not found.
   */
  fun getByHash(hash: String): ScenarioStartingConfigurationEntry? = transaction {
    ScenarioStartingConfigurationTable.selectAll()
        .where { ScenarioStartingConfigurationTable.hash eq hash }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Insert only (fails if hash already exists). Returns the stored row (including generated id).
   */
  fun insert(entry: ScenarioStartingConfigurationEntry): ScenarioStartingConfigurationEntry =
      transaction {
        require(entry.id == null) {
          "insert() expects entry.id == null. Use upsert() or update() otherwise."
        }

        val newId =
            ScenarioStartingConfigurationTable.insertAndGetId { row ->
                  row[hash] = entry.hash
                  row[topLeft] = entry.topLeft
                  row[topCenter] = entry.topCenter
                  row[topRight] = entry.topRight
                  row[middleLeft] = entry.middleLeft
                  row[middleCenter] = entry.middleCenter
                  row[middleRight] = entry.middleRight
                  row[bottomLeft] = entry.bottomLeft
                  row[bottomCenter] = entry.bottomCenter
                  row[bottomRight] = entry.bottomRight
                  row[scenarioFileName] = entry.scenarioFileName
                }
                .value

        // Read-back to ensure we return canonical DB state
        getById(newId) ?: error("Inserted ScenarioStartingConfiguration not found (id=$newId).")
      }

  /**
   * Upsert by unique hash. If an entry with the same hash exists, returns the existing row. If it
   * does not exist, inserts and returns the new row.
   *
   * This implementation is safe for concurrent writers: it attempts insert; on unique violation, it
   * fetches the existing row.
   */
  fun upsert(entry: ScenarioStartingConfigurationEntry): ScenarioStartingConfigurationEntry =
      transaction {
        // If caller already has an id, prefer that row (optional policy).
        entry.id?.let { existing ->
          val byId = getById(existing)
          if (byId != null) return@transaction byId
        }

        // Fast-path: already present by hash
        val existing = getByHash(entry.hash)
        if (existing != null) return@transaction existing

        try {
          insert(entry)
        } catch (e: ExposedSQLException) {
          // Most likely: unique constraint on hash due to concurrent insert
          getByHash(entry.hash) ?: throw e
        }
      }

  private fun ResultRow.toEntry(): ScenarioStartingConfigurationEntry =
      ScenarioStartingConfigurationEntry(
          id = this[ScenarioStartingConfigurationTable.id].value,
          hash = this[ScenarioStartingConfigurationTable.hash],
          topLeft = this[ScenarioStartingConfigurationTable.topLeft],
          topCenter = this[ScenarioStartingConfigurationTable.topCenter],
          topRight = this[ScenarioStartingConfigurationTable.topRight],
          middleLeft = this[ScenarioStartingConfigurationTable.middleLeft],
          middleCenter = this[ScenarioStartingConfigurationTable.middleCenter],
          middleRight = this[ScenarioStartingConfigurationTable.middleRight],
          bottomLeft = this[ScenarioStartingConfigurationTable.bottomLeft],
          bottomCenter = this[ScenarioStartingConfigurationTable.bottomCenter],
          bottomRight = this[ScenarioStartingConfigurationTable.bottomRight],
          scenarioFileName = this[ScenarioStartingConfigurationTable.scenarioFileName],
      )
}
