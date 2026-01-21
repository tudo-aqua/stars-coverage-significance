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
   * Returns the [ScenarioStartingConfigurationEntry] with the given [sequenceNumber] or null if not
   * found.
   *
   * @param sequenceNumber The sequence number of the entry to retrieve.
   * @return The entry with the given sequence number or null if not found.
   */
  fun getBySequenceNumber(sequenceNumber: Long): ScenarioStartingConfigurationEntry? = transaction {
    ScenarioStartingConfigurationTable.selectAll()
        .where { ScenarioStartingConfigurationTable.sequenceNumber eq sequenceNumber }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

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
   * Returns the [ScenarioStartingConfigurationEntry] with the given [humanReadableScenarioId] or
   * null if not found.
   *
   * @param humanReadableScenarioId The human readable scenario id of the entry to retrieve.
   */
  fun getByScenarioByHumanReadableScenarioId(
      humanReadableScenarioId: String
  ): ScenarioStartingConfigurationEntry? = transaction {
    ScenarioStartingConfigurationTable.selectAll()
        .where {
          ScenarioStartingConfigurationTable.humanReadableScenarioId eq humanReadableScenarioId
        }
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
                  row[humanReadableScenarioId] = entry.humanReadableScenarioId
                  row[topLeft] = entry.topLeftVehicleState
                  row[topCenter] = entry.topCenterVehicleState
                  row[topRight] = entry.topRightVehicleState
                  row[middleLeft] = entry.middleLeftVehicleState
                  row[middleCenter] = entry.middleCenterVehicleState
                  row[middleRight] = entry.middleRightVehicleState
                  row[bottomLeft] = entry.bottomLeftVehicleState
                  row[bottomCenter] = entry.bottomCenterVehicleState
                  row[bottomRight] = entry.bottomRightVehicleState
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
        val existing = getByScenarioByHumanReadableScenarioId(entry.humanReadableScenarioId)
        if (existing != null) return@transaction existing

        try {
          insert(entry)
        } catch (e: ExposedSQLException) {
          // Most likely: unique constraint on hash due to concurrent insert
          getByScenarioByHumanReadableScenarioId(entry.humanReadableScenarioId) ?: throw e
        }
      }

  /**
   * Loads scenario IDs from the database whose sequence numbers are within the given range
   * [seqFrom] to [seqTo], inclusive. The IDs are returned in ascending order of their sequence
   * numbers.
   *
   * @param seqFrom The starting sequence number (inclusive).
   * @param seqTo The ending sequence number (inclusive).
   * @return A list of scenario IDs within the specified sequence number range.
   */
  fun loadScenarioIds(seqFrom: Long, seqTo: Long): List<UUID> = transaction {
    ScenarioStartingConfigurationTable.select(ScenarioStartingConfigurationTable.id)
        .where {
          (ScenarioStartingConfigurationTable.sequenceNumber greaterEq seqFrom) and
              (ScenarioStartingConfigurationTable.sequenceNumber lessEq seqTo)
        }
        .orderBy(ScenarioStartingConfigurationTable.sequenceNumber to SortOrder.ASC)
        .map { it[ScenarioStartingConfigurationTable.id].value }
  }

  private fun ResultRow.toEntry(): ScenarioStartingConfigurationEntry =
      ScenarioStartingConfigurationEntry(
          id = this[ScenarioStartingConfigurationTable.id].value,
          sequenceNumber = this[ScenarioStartingConfigurationTable.sequenceNumber],
          humanReadableScenarioId =
              this[ScenarioStartingConfigurationTable.humanReadableScenarioId],
          topLeftVehicleState = this[ScenarioStartingConfigurationTable.topLeft],
          topLeftPosition = this[ScenarioStartingConfigurationTable.topLeftPosition],
          topCenterVehicleState = this[ScenarioStartingConfigurationTable.topCenter],
          topCenterPosition = this[ScenarioStartingConfigurationTable.topCenterPosition],
          topRightVehicleState = this[ScenarioStartingConfigurationTable.topRight],
          topRightPosition = this[ScenarioStartingConfigurationTable.topRightPosition],
          middleLeftVehicleState = this[ScenarioStartingConfigurationTable.middleLeft],
          middleLeftPosition = this[ScenarioStartingConfigurationTable.middleLeftPosition],
          middleCenterVehicleState = this[ScenarioStartingConfigurationTable.middleCenter],
          middleCenterPosition = this[ScenarioStartingConfigurationTable.middleCenterPosition],
          middleRightVehicleState = this[ScenarioStartingConfigurationTable.middleRight],
          middleRightPosition = this[ScenarioStartingConfigurationTable.middleRightPosition],
          bottomLeftVehicleState = this[ScenarioStartingConfigurationTable.bottomLeft],
          bottomLeftPosition = this[ScenarioStartingConfigurationTable.bottomLeftPosition],
          bottomCenterVehicleState = this[ScenarioStartingConfigurationTable.bottomCenter],
          bottomCenterPosition = this[ScenarioStartingConfigurationTable.bottomCenterPosition],
          bottomRightVehicleState = this[ScenarioStartingConfigurationTable.bottomRight],
          bottomRightPosition = this[ScenarioStartingConfigurationTable.bottomRightPosition],
      )
}
