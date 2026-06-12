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

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable.createdAt
import tools.aqua.stars.coverage.significance.db.tables.TSCsTable
import tools.aqua.stars.coverage.significance.db.tables.TSCsTable.possibleTSCInstancesCount
import tools.aqua.stars.coverage.significance.db.tables.TSCsTable.tscJson

@Suppress("StringLiteralDuplication")
/** Repository for [TSCEntry]s. */
object TSCsRepository {

  /**
   * Upsert by unique hash. If an entry with the same hash exists, returns the existing row. If it
   * does not exist, inserts and returns the new row.
   *
   * @param entry Entry to upsert.
   * @return Upserted entry.
   */
  fun upsert(entry: TSCEntry): TSCEntry = transaction {
    val existing = getByJson(entry.tscJson)
    if (existing != null) return@transaction existing

    try {
      insert(entry)
    } catch (e: ExposedSQLException) {
      // Likely unique hash violation due to concurrent insert
      getByJson(entry.tscJson) ?: throw e
    }
  }

  /**
   * Upsert by unique hash. If an entry with the same hash exists, returns the existing id. If it
   * does not exist, inserts and returns the new id.
   *
   * @param entry Entry to upsert.
   * @return Upserted entry id.
   */
  fun upsertAndGetId(entry: TSCEntry): Int = transaction {
    val existing = getByJson(entry.tscJson)
    if (existing != null) return@transaction existing.id ?: error("No id for existing entry.")

    try {
      insertAndGetId(entry)
    } catch (e: ExposedSQLException) {
      // Likely unique hash violation due to concurrent insert
      getByJson(entry.tscJson)?.id ?: throw e
    }
  }

  /**
   * Inserts a new [TSCEntry] into the database if it does not exist.
   *
   * @param entry Entry to insert.
   * @return The ID of the inserted entry.
   */
  fun insertIfMissingAndReturnId(entry: TSCEntry): Int = db {
    require(entry.id == null) { "insert() expects entry.id == null." }

    TSCsTable.insertIgnore { row ->
      row[TSCsTable.tscJson] = entry.tscJson
      row[TSCsTable.createdAt] = entry.createdAt
      row[TSCsTable.possibleTSCInstancesCount] = entry.possibleTSCInstancesCount
    }

    TSCsTable.select(TSCsTable.id)
        .where {
          (tscJson eq entry.tscJson) and
              (possibleTSCInstancesCount eq entry.possibleTSCInstancesCount)
        }
        .limit(1)
        .single()[TSCsTable.id]
        .value
  }

  /**
   * Inserts a new [TSCEntry] into the database.
   *
   * @param entry Entry to insert.
   * @return Inserted entry.
   */
  fun insert(entry: TSCEntry): TSCEntry = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }

    val newId =
        TSCsTable.insertAndGetId { row ->
              row[createdAt] = entry.createdAt
              row[tscJson] = entry.tscJson
              row[possibleTSCInstancesCount] = entry.possibleTSCInstancesCount
            }
            .value

    getById(newId) ?: error("Inserted TSC not found (id=$newId).")
  }

  /**
   * Inserts a new [TSCEntry] into the database and returns its id.
   *
   * @param entry Entry to insert.
   * @return Inserted entry id.
   */
  fun insertAndGetId(entry: TSCEntry): Int = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }

    val newId =
        TSCsTable.insertAndGetId { row ->
              row[createdAt] = entry.createdAt
              row[tscJson] = entry.tscJson
              row[possibleTSCInstancesCount] = entry.possibleTSCInstancesCount
            }
            .value

    getById(newId) ?: error("Inserted TSC not found (id=$newId).")
    newId
  }

  /**
   * Retrieves a [TSCEntry] by its id.
   *
   * @param id Id of the entry to retrieve.
   * @return Entry with the given id or null if not found.
   */
  fun getById(id: Int): TSCEntry? = transaction {
    TSCsTable.selectAll().where { TSCsTable.id eq id }.limit(1).firstOrNull()?.toEntry()
  }

  /**
   * Retrieves a [TSCEntry] by its hash.
   *
   * @param jsonString The JSON string of the TSC.
   * @return Entry with the given hash or null if not found.
   */
  fun getByJson(jsonString: String): TSCEntry? = transaction {
    TSCsTable.selectAll()
        .where { TSCsTable.tscJson eq jsonString }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all [TSCEntry]s.
   *
   * @return All entries.
   */
  fun getAll(): List<TSCEntry> = transaction { TSCsTable.selectAll().map { it.toEntry() } }

  /**
   * Converts a [ResultRow] to a [TSCEntry].
   *
   * @return Converted [TSCEntry].
   */
  private fun ResultRow.toEntry(): TSCEntry =
      TSCEntry(
          id = this[TSCsTable.id].value,
          createdAt = this[TSCsTable.createdAt],
          tscJson = this[TSCsTable.tscJson],
          possibleTSCInstancesCount = this[TSCsTable.possibleTSCInstancesCount],
      )
}
