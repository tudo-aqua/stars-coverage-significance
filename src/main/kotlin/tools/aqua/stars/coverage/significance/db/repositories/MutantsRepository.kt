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

import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable

/** Repository for [MutantEntry]s. */
object MutantsRepository {

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
   * Retrieves a mutant by its mutant key. Returns null if not found.
   *
   * @param mutantKey Mutant key.
   * @return MutantEntry or null if not found.
   */
  fun getByKey(mutantKey: String): MutantEntry? = transaction {
    MutantsTable.selectAll()
        .where { MutantsTable.mutantKey eq mutantKey }
        .limit(1)
        .firstOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all mutants in ascending order of mutantKey.
   *
   * @return List of all MutantEntry.
   */
  fun getAll(): List<MutantEntry> = transaction {
    MutantsTable.selectAll().orderBy(MutantsTable.mutantKey to SortOrder.ASC).map { it.toEntry() }
  }

  /**
   * Retrieves all mutant IDs in ascending order of mutantKey.
   *
   * @return List of all mutant IDs.
   */
  fun getAllIds(): List<UUID> = transaction {
    MutantsTable.select(MutantsTable.id).orderBy(MutantsTable.mutantKey to SortOrder.ASC).map {
      it[MutantsTable.id].value
    }
  }

  /**
   * Inserts a new mutant.
   *
   * @param mutantKey Mutant key.
   * @param payload Optional payload.
   * @param createdAt Creation timestamp (default: now).
   * @return Inserted MutantEntry.
   */
  fun insert(
      mutantKey: String,
      payload: String? = null,
      createdAt: Instant = Instant.now()
  ): MutantEntry = transaction {
    val newId =
        MutantsTable.insertAndGetId {
              it[MutantsTable.createdAt] = createdAt
              it[MutantsTable.mutantKey] = mutantKey
              it[MutantsTable.payload] = payload
            }
            .value

    getById(newId) ?: error("Inserted Mutant not found (id=$newId).")
  }

  /**
   * Upserts a mutant by its mutant key. If a mutant with the given key already exists, it is
   * returned. Otherwise, a new mutant is inserted.
   *
   * @param mutantKey Mutant key.
   * @param payload Optional payload.
   * @return Existing or newly inserted MutantEntry.
   */
  fun upsert(mutantKey: String, payload: String? = null): MutantEntry = transaction {
    val existing = getByKey(mutantKey)
    if (existing != null) return@transaction existing

    try {
      insert(mutantKey, payload)
    } catch (e: ExposedSQLException) {
      // Likely unique constraint violation due to concurrent insert
      getByKey(mutantKey) ?: throw e
    }
  }

  /**
   * Ensures that at least [count] mutants exist in the database. If not, inserts missing mutants
   * with keys "M0001", "M0002", ..., "MXXXX".
   *
   * @param count Number of mutants to ensure.
   * @param payloadProvider Optional function to provide payloads for inserted mutants based on
   *   their 1-based index.
   * @return List of mutant IDs of length [count], in ascending order of mutantKey
   */
  fun ensureMutants(
      count: Int,
      payloadProvider: ((index1Based: Int) -> String?)? = null
  ): List<UUID> = transaction {
    require(count > 0) { "count must be > 0" }

    // Fast path: already enough
    val existing =
        MutantsTable.slice(MutantsTable.id, MutantsTable.mutantKey)
            .selectAll()
            .orderBy(MutantsTable.mutantKey to SortOrder.ASC)
            .limit(count)
            .map { it[MutantsTable.id].value }

    if (existing.size == count) return@transaction existing

    val now = Instant.now()
    val existingKeys =
        MutantsTable.slice(MutantsTable.mutantKey)
            .selectAll()
            .map { it[MutantsTable.mutantKey] }
            .toHashSet()

    val toInsert =
        (1..count).map { i -> "M" + i.toString().padStart(4, '0') }.filterNot { it in existingKeys }

    toInsert.forEachIndexed { idx, key ->
      MutantsTable.insertIgnore {
        it[createdAt] = now
        it[mutantKey] = key
        it[payload] = payloadProvider?.invoke(idx + 1)
      }
    }

    // Return first [count] IDs in canonical order
    MutantsTable.select(MutantsTable.id)
        .orderBy(MutantsTable.mutantKey to SortOrder.ASC)
        .limit(count)
        .map { it[MutantsTable.id].value }
  }

  private fun ResultRow.toEntry(): MutantEntry =
      MutantEntry(
          id = this[MutantsTable.id].value,
          createdAt = this[MutantsTable.createdAt],
          mutantKey = this[MutantsTable.mutantKey],
          payload = this[MutantsTable.payload],
      )
}
