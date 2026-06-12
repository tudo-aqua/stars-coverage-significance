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

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomCenter
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomCenterPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomLeft
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomLeftPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomRight
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.bottomRightPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.humanReadableScenarioId
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleCenter
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleCenterPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleLeft
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleLeftPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleRight
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.middleRightPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topCenter
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topCenterPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topLeft
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topLeftPosition
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topRight
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable.topRightPosition

/** Repository for [ScenarioStartingConfigurationEntry]s. */
object ScenarioStartingConfigurationRepository {

  /**
   * Returns all [ScenarioStartingConfigurationEntry]s in the database.
   *
   * @return A list of all scenario starting configuration entries.
   */
  fun getAll(): List<ScenarioStartingConfigurationEntry> = transaction {
    ScenarioStartingConfigurationTable.selectAll().map { it.toEntry() }
  }

  /** Returns the total count of [ScenarioStartingConfigurationEntry]s in the database. */
  fun getCount(): Long = transaction { ScenarioStartingConfigurationTable.selectAll().count() }

  /** Clears all entries from the [ScenarioStartingConfigurationTable]. */
  fun clearTable() = transaction { ScenarioStartingConfigurationTable.deleteAll() }

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
  fun getById(id: Int): ScenarioStartingConfigurationEntry? = transaction {
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
   * Batch inserts the given list of [entries] into the database.
   *
   * @param entries List of entries to insert.
   */
  fun batchInsert(entries: List<ScenarioStartingConfigurationEntry>) = transaction {
    if (entries.isEmpty()) return@transaction emptyList()

    require(entries.all { it.id == null }) {
      "batchInsert() expects all entry.id == null. Use upsert() or update() otherwise."
    }
    ScenarioStartingConfigurationTable.batchInsert(entries, shouldReturnGeneratedValues = true) {
        entry ->
      this[humanReadableScenarioId] = entry.humanReadableScenarioId

      this[topLeft] = entry.topLeftVehicleState
      this[topCenter] = entry.topCenterVehicleState
      this[topRight] = entry.topRightVehicleState
      this[middleLeft] = entry.middleLeftVehicleState
      this[middleCenter] = entry.middleCenterVehicleState
      this[middleRight] = entry.middleRightVehicleState
      this[bottomLeft] = entry.bottomLeftVehicleState
      this[bottomCenter] = entry.bottomCenterVehicleState
      this[bottomRight] = entry.bottomRightVehicleState

      this[topLeftPosition] = entry.topLeftPosition
      this[topCenterPosition] = entry.topCenterPosition
      this[topRightPosition] = entry.topRightPosition
      this[middleLeftPosition] = entry.middleLeftPosition
      this[middleCenterPosition] = entry.middleCenterPosition
      this[middleRightPosition] = entry.middleRightPosition
      this[bottomLeftPosition] = entry.bottomLeftPosition
      this[bottomCenterPosition] = entry.bottomCenterPosition
      this[bottomRightPosition] = entry.bottomRightPosition
    }
  }

  /**
   * Batch inserts the given list of [entries] into the database and returns the inserted entries
   * with their generated IDs.
   *
   * @param entries List of entries to insert.
   * @return List of inserted entries with their generated IDs.
   */
  fun batchInsertAndReturnId(
      entries: List<ScenarioStartingConfigurationEntry>
  ): List<ScenarioStartingConfigurationEntry> = transaction {
    if (entries.isEmpty()) return@transaction emptyList()

    require(entries.all { it.id == null }) {
      "batchInsert() expects all entry.id == null. Use upsert() or update() otherwise."
    }

    val insertedIds: List<Int> =
        ScenarioStartingConfigurationTable.batchInsert(
                entries, shouldReturnGeneratedValues = true) { entry ->
                  this[humanReadableScenarioId] = entry.humanReadableScenarioId

                  this[topLeft] = entry.topLeftVehicleState
                  this[topCenter] = entry.topCenterVehicleState
                  this[topRight] = entry.topRightVehicleState
                  this[middleLeft] = entry.middleLeftVehicleState
                  this[middleCenter] = entry.middleCenterVehicleState
                  this[middleRight] = entry.middleRightVehicleState
                  this[bottomLeft] = entry.bottomLeftVehicleState
                  this[bottomCenter] = entry.bottomCenterVehicleState
                  this[bottomRight] = entry.bottomRightVehicleState

                  this[topLeftPosition] = entry.topLeftPosition
                  this[topCenterPosition] = entry.topCenterPosition
                  this[topRightPosition] = entry.topRightPosition
                  this[middleLeftPosition] = entry.middleLeftPosition
                  this[middleCenterPosition] = entry.middleCenterPosition
                  this[middleRightPosition] = entry.middleRightPosition
                  this[bottomLeftPosition] = entry.bottomLeftPosition
                  this[bottomCenterPosition] = entry.bottomCenterPosition
                  this[bottomRightPosition] = entry.bottomRightPosition
                }
            .map { row -> row[ScenarioStartingConfigurationTable.id].value }

    insertedIds.map { id ->
      getById(id) ?: error("Inserted ScenarioStartingConfiguration not found (id=$id).")
    }
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

                  row[topLeftPosition] = entry.topLeftPosition
                  row[topCenterPosition] = entry.topCenterPosition
                  row[topRightPosition] = entry.topRightPosition
                  row[middleLeftPosition] = entry.middleLeftPosition
                  row[middleCenterPosition] = entry.middleCenterPosition
                  row[middleRightPosition] = entry.middleRightPosition
                  row[bottomLeftPosition] = entry.bottomLeftPosition
                  row[bottomCenterPosition] = entry.bottomCenterPosition
                  row[bottomRightPosition] = entry.bottomRightPosition
                }
                .value

        // Read-back to ensure to return canonical DB state
        getById(newId) ?: error("Inserted ScenarioStartingConfiguration not found (id=$newId).")
      }

  /**
   * Returns the maximum sequence number present in the [ScenarioStartingConfigurationTable]. If the
   * table is empty, returns 0.
   *
   * @return The maximum sequence number or 0 if the table is empty.
   */
  fun getMaxSequenceNumber(): Long = db {
    ScenarioStartingConfigurationTable.select(
            ScenarioStartingConfigurationTable.sequenceNumber.max())
        .firstOrNull()
        ?.get(ScenarioStartingConfigurationTable.sequenceNumber.max()) ?: 0L
  }

  /**
   * Inserts the given [entry] into the database if an entry with the same values does not already
   * exist.
   *
   * @param entry Entry to insert.
   */
  fun insertIfMissing(entry: ScenarioStartingConfigurationEntry) = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }
    ScenarioStartingConfigurationTable.insertIgnore { row ->
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

      row[topLeftPosition] = entry.topLeftPosition
      row[topCenterPosition] = entry.topCenterPosition
      row[topRightPosition] = entry.topRightPosition
      row[middleLeftPosition] = entry.middleLeftPosition
      row[middleCenterPosition] = entry.middleCenterPosition
      row[middleRightPosition] = entry.middleRightPosition
      row[bottomLeftPosition] = entry.bottomLeftPosition
      row[bottomCenterPosition] = entry.bottomCenterPosition
      row[bottomRightPosition] = entry.bottomRightPosition
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
  fun loadScenarioIds(seqFrom: Long, seqTo: Long): List<Int> = transaction {
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
          humanReadableScenarioId = this[humanReadableScenarioId],
          topLeftVehicleState = this[topLeft],
          topLeftPosition = this[topLeftPosition],
          topCenterVehicleState = this[topCenter],
          topCenterPosition = this[topCenterPosition],
          topRightVehicleState = this[topRight],
          topRightPosition = this[topRightPosition],
          middleLeftVehicleState = this[middleLeft],
          middleLeftPosition = this[middleLeftPosition],
          middleCenterVehicleState = this[middleCenter],
          middleCenterPosition = this[middleCenterPosition],
          middleRightVehicleState = this[middleRight],
          middleRightPosition = this[middleRightPosition],
          bottomLeftVehicleState = this[bottomLeft],
          bottomLeftPosition = this[bottomLeftPosition],
          bottomCenterVehicleState = this[bottomCenter],
          bottomCenterPosition = this[bottomCenterPosition],
          bottomRightVehicleState = this[bottomRight],
          bottomRightPosition = this[bottomRightPosition],
      )
}
