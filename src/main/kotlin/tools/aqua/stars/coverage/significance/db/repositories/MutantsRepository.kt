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
          m[MutantsTable.mutantKey] = mutant.mutantKey

          m[MutantsTable.c1Level] = mutant.c1Level
          m[MutantsTable.c2Level] = mutant.c2Level
          m[MutantsTable.c3Level] = mutant.c3Level
          m[MutantsTable.c4Level] = mutant.c4Level
          m[MutantsTable.c5Level] = mutant.c5Level

          m[MutantsTable.headwayErrorCoefficient] = mutant.headwayErrorCoefficient
          m[MutantsTable.speedDifferenceErrorCoefficient] = mutant.speedDifferenceErrorCoefficient

          m[MutantsTable.headwayChangePerceptionThreshold] = mutant.headwayChangePerceptionThreshold
          m[MutantsTable.speedDifferenceChangePerceptionThreshold] =
              mutant.speedDifferenceChangePerceptionThreshold
          m[MutantsTable.maximalReactionTime] = mutant.maximalReactionTime

          m[MutantsTable.errorNoiseIntensityCoefficient] = mutant.errorNoiseIntensityCoefficient
          m[MutantsTable.errorTimeScaleCoefficient] = mutant.errorTimeScaleCoefficient

          m[MutantsTable.initialAwareness] = mutant.initialAwareness
          m[MutantsTable.minAwareness] = mutant.minAwareness

          m[MutantsTable.speedFactor] = mutant.speedFactor
          m[MutantsTable.lcAssertive] = mutant.lcAssertive
          m[MutantsTable.lcSpeedGain] = mutant.lcSpeedGain
          m[MutantsTable.lcCooperative] = mutant.lcCooperative

          m[MutantsTable.tau] = mutant.tau
          m[MutantsTable.sigma] = mutant.sigma
          m[MutantsTable.minGap] = mutant.minGap
          m[MutantsTable.speedDeviation] = mutant.speedDeviation
          m[MutantsTable.maxSpeed] = mutant.maxSpeed
        }
        ?.value ?: getByKey(mutant.mutantKey)?.id
  }

  /**
   * Inserts multiple mutants into the database.
   *
   * @param mutants List of MutantEntry to insert.
   */
  fun insertAll(mutants: List<MutantEntry>) = transaction {
    MutantsTable.batchInsert(mutants, ignore = false) { m ->
          this[MutantsTable.createdAt] = m.createdAt
          this[MutantsTable.mutantKey] = m.mutantKey

          this[MutantsTable.c1Level] = m.c1Level
          this[MutantsTable.c2Level] = m.c2Level
          this[MutantsTable.c3Level] = m.c3Level
          this[MutantsTable.c4Level] = m.c4Level
          this[MutantsTable.c5Level] = m.c5Level

          this[MutantsTable.headwayErrorCoefficient] = m.headwayErrorCoefficient
          this[MutantsTable.speedDifferenceErrorCoefficient] = m.speedDifferenceErrorCoefficient

          this[MutantsTable.headwayChangePerceptionThreshold] = m.headwayChangePerceptionThreshold
          this[MutantsTable.speedDifferenceChangePerceptionThreshold] =
              m.speedDifferenceChangePerceptionThreshold
          this[MutantsTable.maximalReactionTime] = m.maximalReactionTime

          this[MutantsTable.errorNoiseIntensityCoefficient] = m.errorNoiseIntensityCoefficient
          this[MutantsTable.errorTimeScaleCoefficient] = m.errorTimeScaleCoefficient

          this[MutantsTable.initialAwareness] = m.initialAwareness
          this[MutantsTable.minAwareness] = m.minAwareness

          this[MutantsTable.speedFactor] = m.speedFactor
          this[MutantsTable.lcAssertive] = m.lcAssertive
          this[MutantsTable.lcSpeedGain] = m.lcSpeedGain
          this[MutantsTable.lcCooperative] = m.lcCooperative

          this[MutantsTable.tau] = m.tau
          this[MutantsTable.sigma] = m.sigma
          this[MutantsTable.minGap] = m.minGap
          this[MutantsTable.speedDeviation] = m.speedDeviation
          this[MutantsTable.maxSpeed] = m.maxSpeed
        }
        .map { it.toEntry() }
  }

  /**
   * Retrieves all mutants in ascending order of mutantKey.
   *
   * @return List of all MutantEntry.
   */
  fun listAll(): List<MutantEntry> = transaction {
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
          mutantKey = this[MutantsTable.mutantKey],
          c1Level = this[MutantsTable.c1Level],
          c2Level = this[MutantsTable.c2Level],
          c3Level = this[MutantsTable.c3Level],
          c4Level = this[MutantsTable.c4Level],
          c5Level = this[MutantsTable.c5Level],
          headwayErrorCoefficient = this[MutantsTable.headwayErrorCoefficient],
          speedDifferenceErrorCoefficient = this[MutantsTable.speedDifferenceErrorCoefficient],
          headwayChangePerceptionThreshold = this[MutantsTable.headwayChangePerceptionThreshold],
          speedDifferenceChangePerceptionThreshold =
              this[MutantsTable.speedDifferenceChangePerceptionThreshold],
          maximalReactionTime = this[MutantsTable.maximalReactionTime],
          errorNoiseIntensityCoefficient = this[MutantsTable.errorNoiseIntensityCoefficient],
          errorTimeScaleCoefficient = this[MutantsTable.errorTimeScaleCoefficient],
          initialAwareness = this[MutantsTable.initialAwareness],
          minAwareness = this[MutantsTable.minAwareness],
          speedFactor = this[MutantsTable.speedFactor],
          lcAssertive = this[MutantsTable.lcAssertive],
          lcSpeedGain = this[MutantsTable.lcSpeedGain],
          lcCooperative = this[MutantsTable.lcCooperative],
          maxSpeed = this[MutantsTable.maxSpeed],
          speedDeviation = this[MutantsTable.speedDeviation],
          sigma = this[MutantsTable.sigma],
          tau = this[MutantsTable.tau],
          minGap = this[MutantsTable.minGap],
      )
}
