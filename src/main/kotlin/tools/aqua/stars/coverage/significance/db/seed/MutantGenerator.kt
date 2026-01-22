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

/**
 * Object responsible for generating all mutant entries based on predefined parameter bundles.
 *
 * Each mutant is defined by a unique combination of parameters from five different bundles (C1 to
 * C5). The total number of mutants generated is 576, derived from the combinations of the levels in
 * each bundle.
 *
 * @property C1 Bundle of parameters related to error coefficients.
 * @property C2 Bundle of parameters related to perception thresholds and reaction time.
 * @property C3 Bundle of parameters related to error noise and time scale.
 * @property C4 Bundle of parameters related to driver awareness.
 * @property C5 Bundle of parameters related to speed and lane changing behavior.
 */
object MutantGenerator {

  /**
   * Bundle C1: (headwayErrorCoefficient, speedDifferenceErrorCoefficient)
   *
   * C1–1 : (0.75, 0.15), C1–2 : (1.00, 0.30), C1–3 : (1.30, 0.60), C1–4 : (1.70, 0.90).
   */
  private val C1 =
      listOf(
          0.75 to 0.15,
          1.00 to 0.30,
          1.30 to 0.60,
          1.70 to 0.90,
      )

  /**
   * Bundle C2: (headwayChangePerceptionThreshold, speedDifferenceChangePerceptionThreshold,
   * maximalReactionTime)
   *
   * C2–1 : (0.10, 0.10, 0.30), C2–2 : (0.25, 0.25, 0.80), C2–3 : (0.50, 0.50, 1.60), C2–4 : (0.75,
   * 0.75, 2.50).
   */
  private val C2 =
      listOf(
          Triple(0.10, 0.10, 0.30),
          Triple(0.25, 0.25, 0.80),
          Triple(0.50, 0.50, 1.60),
          Triple(0.75, 0.75, 2.50),
      )

  /**
   * Bundle C3: (errorNoiseIntensityCoefficient, errorTimeScaleCoefficient)
   *
   * C3–1 : (0.20, 20), C3–2 : (1.00, 20), C3–3 : (0.20, 400), C3–4 : (1.00, 400).
   */
  private val C3 =
      listOf(
          0.20 to 20.0,
          1.00 to 20.0,
          0.20 to 400.0,
          1.00 to 400.0,
      )

  /**
   * Bundle C4: (initialAwareness, minAwareness)
   *
   * C4–1 : (0.70, 0.08), C4–2 : (0.50, 0.05), C4–3 : (0.30, 0.02).
   */
  private val C4 =
      listOf(
          0.70 to 0.08,
          0.50 to 0.05,
          0.30 to 0.02,
      )

  /**
   * Bundle C5: (speedFactor, lcAssertive, lcSpeedGain, lcCooperative)
   *
   * C5–1 : (1.00, 0.50, 1.00, 1.00), C5–2 : (1.10, 0.70, 1.50, 0.50), C5–3 : (1.20, 0.90, 2.00,
   * 0.00).
   */
  private val C5 =
      listOf(
          listOf(1.00, 0.50, 1.00, 1.00),
          listOf(1.10, 0.70, 1.50, 0.50),
          listOf(1.20, 0.90, 2.00, 0.00),
      )

  /**
   * Generates all possible mutant entries by combining parameters from bundles C1 to C5.
   *
   * @param now The timestamp to be used for the creation time of each mutant entry. Defaults to the
   *   current instant.
   * @return A list of all generated [MutantEntry] instances.
   */
  fun generateAll(now: Instant = Instant.now()): List<MutantEntry> {
    val mutantEntries = ArrayList<MutantEntry>(576)
    var index = 1

    for ((c1Index, c1) in C1.withIndex()) {
      for ((c2Index, c2) in C2.withIndex()) {
        for ((c3Index, c3) in C3.withIndex()) {
          for ((c4Index, c4) in C4.withIndex()) {
            for ((c5Index, c5) in C5.withIndex()) {

              val mutantKey = "M" + index.toString().padStart(4, '0')
              index++

              mutantEntries +=
                  MutantEntry(
                      id = null,
                      createdAt = now,
                      mutantKey = mutantKey,
                      c1Level = c1Index + 1,
                      c2Level = c2Index + 1,
                      c3Level = c3Index + 1,
                      c4Level = c4Index + 1,
                      c5Level = c5Index + 1,
                      headwayErrorCoefficient = c1.first,
                      speedDifferenceErrorCoefficient = c1.second,
                      headwayChangePerceptionThreshold = c2.first,
                      speedDifferenceChangePerceptionThreshold = c2.second,
                      maximalReactionTime = c2.third,
                      errorNoiseIntensityCoefficient = c3.first,
                      errorTimeScaleCoefficient = c3.second,
                      initialAwareness = c4.first,
                      minAwareness = c4.second,
                      speedFactor = c5[0],
                      lcAssertive = c5[1],
                      lcSpeedGain = c5[2],
                      lcCooperative = c5[3],
                  )
            }
          }
        }
      }
    }

    check(mutantEntries.size == 576) { "Expected 576 mutants, got ${mutantEntries.size}" }
    return mutantEntries
  }

  /**
   * Seeds the database with mutant entries if it is currently empty.
   *
   * @param numberOfMutants Number of mutants to seed.
   * @return List of inserted mutant entry IDs, or an empty list if the database was not empty.
   */
  fun seed(numberOfMutants: Int? = null): List<UUID> {
    val existing = MutantsRepository.getAllIds()
    // When all 576 mutants exist and the number of mutants to seed is not specified, return early.
    if (numberOfMutants == null && existing.isNotEmpty() && existing.size == 576) {
      println("Database already seeded with 576 mutants.")
      return existing
    }

    // A new number of mutants was specified. Therefore, clean previously generated mutants.
    MutantsRepository.cleanTable()

    var mutants = generateAll()
    if (numberOfMutants != null) {
      mutants = mutants.take(numberOfMutants)
    }
    val mutantEntryIds = MutantsRepository.insertAll(mutants).mapNotNull { it.id }
    println("Seeded ${mutants.size} mutants.")
    return mutantEntryIds
  }
}
