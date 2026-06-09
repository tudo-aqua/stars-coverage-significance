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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.distinctMutantIds
import tools.aqua.stars.coverage.significance.mutantFailuresFiltered

/** Counts the number of scenarios killing a mutant per mutant. */
object CountOfScenariosKillingAMutantPerMutantPostEvaluation {
  /** Evaluates the count of scenarios killing a mutant per mutant. */
  fun evaluate() {
    println("Starting CountOfScenariosKillingAMutantPerMutantPostEvaluation.")
    val countOfScenariosKillingAMutant = calculateCountOfScenariosKillingMutant()

    val countOfKillingScenariosPerMutantFiltered =
        countOfScenariosKillingAMutant.filter { it.second < 160 } // 160 = #TSC instances

    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_scenarios_killing_mutant_per_mutant",
            "countOfScenariosKillingMutantPerMutant.csv",
        )
    Files.createDirectories(path.parent)
    path.writeText(
        countOfKillingScenariosPerMutantFiltered.joinToString(
            prefix = "Mutant, Count of scenarios killing mutant\n", separator = "\n") {
              "${it.first},${it.second}"
            })
    println("Finished CountOfScenariosKillingAMutantPerMutantPostEvaluation.")
  }

  private fun calculateCountOfScenariosKillingMutant(): List<Pair<UUID, Int>> {
    val countOfScenariosKillingMutant: List<Pair<UUID, Int>> =
        distinctMutantIds
            .map { id ->
              id to
                  mutantFailuresFiltered
                      .filter { it.mutantID == id }
                      .map { it.currentTSCInstance }
                      .toSet()
                      .size
            }
            .sortedBy { it.second }

    return countOfScenariosKillingMutant
  }
}
