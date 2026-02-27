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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable

/** Repository for [MutantEntry]s. */
object MutantsRepository {

  /** Removes all entries from the database. */
  fun cleanTable() = transaction { MutantsTable.deleteAll() }

  /**
   * Retrieves a mutant by its ID. Returns null if not found.
   *
   * @param id Mutant ID.
   * @return MutantEntry or null if not found.
   */
  fun getById(id: UUID): MutantEntry? = transaction {
    MutantsTable.selectAll().where { MutantsTable.id eq id }.limit(1).firstOrNull()?.toEntry()
  }

  /**
   * Retrieves a mutant by its mutant number. Returns null if not found.
   *
   * @param mutantNumber Mutant number.
   * @return MutantEntry or null if not found.
   */
  fun getByNumber(mutantNumber: Int): MutantEntry? = transaction {
    MutantsTable.selectAll()
        .where { MutantsTable.mutantNumber eq mutantNumber }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Inserts a mutant into the database if a mutant with the same mutant key does not already exist.
   * Returns the ID of the existing or newly inserted mutant.
   *
   * @param mutant MutantEntry to insert.
   * @return ID of the existing or newly inserted mutant, or null if insertion failed for some
   *   reason.
   * @throws ExposedSQLException if the insertion fails due to a database error other than a unique
   *   constraint violation on the mutant key.
   */
  fun insertIfMissingAndGetId(mutant: MutantEntry): UUID? = db {
    MutantsTable.insertIgnoreAndGetId { m ->
          m[MutantsTable.createdAt] = mutant.createdAt
          m[MutantsTable.mutantNumber] = mutant.mutantNumber
          m[MutantsTable.className] = mutant.className
        }
        ?.value ?: getByNumber(mutant.mutantNumber)?.id
  }

  /**
   * Inserts multiple mutants into the database.
   *
   * @param mutants List of MutantEntry to insert.
   */
  fun insertAll(mutants: List<MutantEntry>) = transaction {
    MutantsTable.batchInsert(mutants, ignore = false) { m ->
          this[MutantsTable.createdAt] = m.createdAt
          this[MutantsTable.mutantNumber] = m.mutantNumber
          this[MutantsTable.className] = m.className
        }
        .map { it.toEntry() }
  }

  /**
   * Retrieves all mutants in ascending order of mutantKey.
   *
   * @return List of all MutantEntry.
   */
  fun listAll(): List<MutantEntry> = transaction {
    MutantsTable.selectAll().orderBy(MutantsTable.mutantNumber to SortOrder.ASC).map {
      it.toEntry()
    }
  }

  /**
   * Retrieves all mutant IDs in ascending order of mutantKey.
   *
   * @return List of all mutant IDs.
   */
  fun getAllIds(): List<UUID> = transaction {
    MutantsTable.select(MutantsTable.id).orderBy(MutantsTable.mutantNumber to SortOrder.ASC).map {
      it[MutantsTable.id].value
    }
  }

  /**
   * Counts the total number of mutants in the database.
   *
   * @return Total number of mutants.
   */
  fun count(): Long = transaction { MutantsTable.selectAll().count() }

  /**
   * Converts a database [ResultRow] to a [MutantEntry].
   *
   * @return Converted MutantEntry.
   * @receiver ResultRow to convert.
   */
  private fun ResultRow.toEntry(): MutantEntry =
      MutantEntry(
          id = this[MutantsTable.id].value,
          createdAt = this[MutantsTable.createdAt],
          mutantNumber = this[MutantsTable.mutantNumber],
          className = this[MutantsTable.className],
      )
}
