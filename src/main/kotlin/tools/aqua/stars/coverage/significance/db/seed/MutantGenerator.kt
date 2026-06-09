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

package tools.aqua.stars.coverage.significance.db.seed

import java.time.Instant
import java.util.UUID
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.sumo.Mutant
import tools.aqua.stars.sumo.mutants.AutopilotMutants

/** Generates mutant entries for the database. */
object MutantGenerator {

  /** The list of all [Mutant]s. */
  val autopilotMutants = AutopilotMutants.byIndex

  /** The number of mutants. */
  val expectedMutantCount = autopilotMutants.size

  /**
   * Generates all possible mutant entries by combining parameters from bundles C1 to C5.
   *
   * @param now The timestamp to be used for the creation time of each mutant entry. Defaults to the
   *   current instant.
   * @return A list of all generated [MutantEntry] instances.
   */
  fun generateAll(now: Instant = Instant.now()): List<MutantEntry> {
    val mutantEntries = mutableListOf<MutantEntry>()

    for (mutant in autopilotMutants) {
      mutantEntries +=
          MutantEntry(
              id = null,
              createdAt = now,
              mutantNumber = mutant.key,
              className = mutant.value.simpleName ?: error("No simple name for ${mutant.value}"),
          )
    }

    check(mutantEntries.size == expectedMutantCount) {
      "Expected $expectedMutantCount mutants, got ${mutantEntries.size}"
    }
    return mutantEntries
  }

  /**
   * Seeds the database with a baseline mutant entry that serves as a reference point for all other
   * mutants.
   *
   * @return The ID of the inserted baseline mutant entry.
   * @throws IllegalStateException if the baseline mutant entry could not be inserted.
   */
  fun seedBaseline(): UUID {
    println("Seeding database with baseline mutant...")
    val baseLineMutant =
        MutantEntry(
            createdAt = Instant.now(),
            mutantNumber = -1,
            className = "Autopilot",
        )

    val mutantEntryId = MutantsRepository.insertIfMissingAndGetId(baseLineMutant)
    checkNotNull(mutantEntryId) { "Failed to insert baseline mutant." }
    println("Seeded baseline mutant with ID: $mutantEntryId")
    return mutantEntryId
  }

  /**
   * Seeds the database with mutant entries if it is currently empty.
   *
   * @param numberOfMutants Number of mutants to seed.
   * @param onlyInsertMutantsWithMutantNumber List of mutant numbers to insert.
   * @return List of inserted mutant entry IDs, or an empty list if the database was not empty.
   */
  fun seed(
      numberOfMutants: Int? = null,
      onlyInsertMutantsWithMutantNumber: List<Int>? = null
  ): List<UUID> {
    println("Seeding database with mutants...")
    var mutants = generateAll()
    if (onlyInsertMutantsWithMutantNumber != null) {
      mutants = mutants.filter { it.mutantNumber in onlyInsertMutantsWithMutantNumber }
    }
    if (numberOfMutants != null) {
      mutants = mutants.take(numberOfMutants)
    }
    val mutantEntryIds = MutantsRepository.insertAll(mutants).mapNotNull { it.id }
    println("Seeded ${mutants.size} mutants.")
    return mutantEntryIds
  }
}
