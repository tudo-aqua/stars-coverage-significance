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
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioCell
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable

/**
 * Repository for [ScenarioStartingConfigurationEntry]s. It has no method that deletes scenarios:
 * the result tables reference them with `ON DELETE CASCADE`.
 */
object ScenarioStartingConfigurationRepository {

  private val scenarios = ScenarioStartingConfigurationTable

  /**
   * Returns all [ScenarioStartingConfigurationEntry]s in the database.
   *
   * @return A list of all scenario starting configuration entries.
   */
  fun getAll(): List<ScenarioStartingConfigurationEntry> = transaction {
    scenarios.selectAll().map { it.toEntry() }
  }

  /** Returns the total count of [ScenarioStartingConfigurationEntry]s in the database. */
  fun getCount(): Long = transaction { scenarios.selectAll().count() }

  /**
   * Returns the [ScenarioStartingConfigurationEntry] with the given [sequenceNumber] or null if not
   * found.
   *
   * @param sequenceNumber The sequence number of the entry to retrieve.
   * @return The entry with the given sequence number or null if not found.
   */
  fun getBySequenceNumber(sequenceNumber: Long): ScenarioStartingConfigurationEntry? = transaction {
    scenarios
        .selectAll()
        .where { scenarios.sequenceNumber eq sequenceNumber }
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
    scenarios.selectAll().where { scenarios.id eq id }.limit(1).firstOrNull()?.toEntry()
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
    scenarios
        .selectAll()
        .where { scenarios.humanReadableScenarioId eq humanReadableScenarioId }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Returns the entry with the highest sequence number, i.e. the one inserted last, or null if the
   * table is empty.
   */
  fun getLastInserted(): ScenarioStartingConfigurationEntry? = transaction {
    scenarios
        .selectAll()
        .orderBy(scenarios.sequenceNumber to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Inserts the given [entries] in one transaction, in list order.
   *
   * @param entries Entries without id, each with an enumeration and a position draw index.
   */
  fun batchInsert(entries: List<ScenarioStartingConfigurationEntry>) {
    if (entries.isEmpty()) return
    require(entries.all { it.id == null }) { "batchInsert() expects all entry.id == null." }
    transaction {
      scenarios.batchInsert(entries, shouldReturnGeneratedValues = false) { entry ->
        this[scenarios.humanReadableScenarioId] = entry.humanReadableScenarioId
        this[scenarios.enumerationIndex] =
            checkNotNull(entry.enumerationIndex) { "Missing enumeration index: $entry" }
        this[scenarios.positionDrawIndex] =
            checkNotNull(entry.positionDrawIndex) { "Missing position draw index: $entry" }
        scenarios.cells.zip(entry.cells).forEach { (columns, cell) ->
          this[columns.type] = cell.type
          this[columns.positionMeters] = cell.positionMeters
          this[columns.speedKmh] = cell.speedKmh
        }
      }
    }
  }

  /**
   * Returns the maximum sequence number present in the [ScenarioStartingConfigurationTable]. If the
   * table is empty, returns 0.
   *
   * @return The maximum sequence number or 0 if the table is empty.
   */
  fun getMaxSequenceNumber(): Long = db {
    scenarios
        .select(scenarios.sequenceNumber.max())
        .firstOrNull()
        ?.get(scenarios.sequenceNumber.max()) ?: 0L
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
    scenarios
        .select(scenarios.id)
        .where {
          (scenarios.sequenceNumber greaterEq seqFrom) and (scenarios.sequenceNumber lessEq seqTo)
        }
        .orderBy(scenarios.sequenceNumber to SortOrder.ASC)
        .map { it[scenarios.id].value }
  }

  private fun ResultRow.toEntry(): ScenarioStartingConfigurationEntry =
      ScenarioStartingConfigurationEntry(
          id = this[scenarios.id].value,
          sequenceNumber = this[scenarios.sequenceNumber],
          humanReadableScenarioId = this[scenarios.humanReadableScenarioId],
          enumerationIndex = this[scenarios.enumerationIndex],
          positionDrawIndex = this[scenarios.positionDrawIndex],
          cells =
              scenarios.cells.map { columns ->
                ScenarioCell(
                    type = this[columns.type],
                    positionMeters = this[columns.positionMeters],
                    speedKmh = this[columns.speedKmh],
                )
              },
      )
}
