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
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.tables.EvaluationRunsTable

/** Repository for managing [EvaluationRunEntry] in the database. */
object EvaluationRunsRepository {

  /**
   * Inserts a new [EvaluationRunEntry] into the database.
   *
   * @param entry Entry to insert.
   * @return ID of inserted entry.
   */
  fun insertAndGetId(entry: EvaluationRunEntry): UUID = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }

    val newId = EvaluationRunsTable.insertAndGetId { row -> row[createdAt] = entry.createdAt }.value

    getById(newId) ?: error("Inserted EvaluationRun not found (id=$newId).")
    newId
  }

  /**
   * Inserts a new [EvaluationRunEntry] into the database.
   *
   * @param entry Entry to insert.
   * @return Inserted entry.
   */
  fun insert(entry: EvaluationRunEntry): EvaluationRunEntry = transaction {
    require(entry.id == null) { "insert() expects entry.id == null." }

    val newId = EvaluationRunsTable.insertAndGetId { row -> row[createdAt] = entry.createdAt }.value

    getById(newId) ?: error("Inserted EvaluationRun not found (id=$newId).")
  }

  /**
   * Retrieves an [EvaluationRunEntry] by its id.
   *
   * @param id Id of the entry to retrieve.
   * @return Entry with the given id or null if not found.
   */
  fun getById(id: UUID): EvaluationRunEntry? = transaction {
    EvaluationRunsTable.selectAll()
        .where { EvaluationRunsTable.id eq id }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all [EvaluationRunEntry]s.
   *
   * @return All entries.
   */
  fun getAll(): List<EvaluationRunEntry> = transaction {
    EvaluationRunsTable.selectAll().map { it.toEntry() }
  }

  /**
   * Converts a [ResultRow] to an [EvaluationRunEntry].
   *
   * @return Converted [EvaluationRunEntry].
   */
  private fun ResultRow.toEntry(): EvaluationRunEntry =
      EvaluationRunEntry(
          id = this[EvaluationRunsTable.id].value,
          createdAt = this[EvaluationRunsTable.createdAt],
      )
}
