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
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.DuplicateMutantEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry
import tools.aqua.stars.coverage.significance.db.tables.DuplicateMutantsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable

/** Repository for [MutantEntry]s. */
object DuplicateMutantsRepository {

  /** Removes all entries from the database. */
  fun cleanTable() = transaction { DuplicateMutantsTable.deleteAll() }

  /**
   * Retrieves all mutants.
   *
   * @return MutantEntry or null if not found.
   */
  fun getAll(): List<DuplicateMutantEntry> = transaction {
    DuplicateMutantsTable.selectAll().map { it.toEntry() }
  }

  /**
   * Inserts multiple mutants into the database.
   *
   * @param mutants List of MutantEntry to insert.
   */
  fun insertAll(mutants: List<DuplicateMutantEntry>) = transaction {
    DuplicateMutantsTable.batchInsert(mutants, ignore = false) {}.map { it.toEntry() }
  }

  /**
   * Converts a database [ResultRow] to a [DuplicateMutantEntry].
   *
   * @return Converted MutantEntry.
   * @receiver ResultRow to convert.
   */
  private fun ResultRow.toEntry(): DuplicateMutantEntry =
      DuplicateMutantEntry(id = this[MutantsTable.id].value)
}
