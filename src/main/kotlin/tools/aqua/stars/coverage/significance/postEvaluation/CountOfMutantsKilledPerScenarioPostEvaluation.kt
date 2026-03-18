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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure

object CountOfMutantsKilledPerScenarioPostEvaluation {
  fun evaluate(
      allTSCInstances: List<Pair<UUID, String>>,
      randomTrafficTSCInstances: List<UUID>,
      filteredMutantFailures: List<MutantFailure>,
      featureToFlagActive: String
  ) {
    println("Starting CountOfMutantsKilledPerScenarioPostEvaluation.")
    val longtail =
        allTSCInstances
            .map { it to randomTrafficTSCInstances.count { t -> t == it.first } }
            .sortedByDescending { it.second }

    val values: List<Quadruple<UUID, Int, Int, Boolean>> =
        longtail.map { l ->
          Quadruple(
              l.first.first,
              l.second,
              filteredMutantFailures
                  .filter { it.startingScenario == l.first.first }
                  .map { it.mutantID }
                  .toSet()
                  .size,
              l.first.second.contains(featureToFlagActive))
        }

    values.sortedByDescending { it.third }.take(5).forEach { println(it.first) }

    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_mutants_killed_per_scenario",
            "countOfMutantsKilledPerScenario.csv",
        )
    Files.createDirectories(path.parent)
    path.writeText(
        values.joinToString(
            prefix =
                "Scenario, Frequency in longtail, Count of mutants killed, Feature '$featureToFlagActive' active\n",
            separator = "\n") {
              "${it.first},${it.second},${it.third},${it.fourth}"
            })
    println("Finished CountOfMutantsKilledPerScenarioPostEvaluation.")
  }
}
