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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON

object AccidentsKillingPerScenarioPostEvaluation {
  fun evaluate(
      longtailDistribution: List<Pair<ScenarioIdAndJSON, Int>>,
      filteredMutantFailures: List<MutantFailure>
  ) {
    val mutantsKilledByAccident =
        filteredMutantFailures.filter {
          it.monitorBitmask and Monitors.G0Accidents.mask == Monitors.G0Accidents.mask
        }
    val mutantsKilled = mutantsKilledByAccident.map { it.mutantID }.toSet()
    println("Total mutants killed: " + mutantsKilled.size)

    // 160 x 14: Scenario -> Map<MutantID, Killed?>
    val killingMatrix: Map<UUID, MutableMap<UUID, Boolean>> =
        longtailDistribution.associate {
          it.first.scenarioId to
              mutantsKilledByAccident.associate { t -> t.mutantID to false }.toMutableMap()
        }

    mutantsKilledByAccident.forEach { mutantFailure ->
      killingMatrix[mutantFailure.tscInstance]!![mutantFailure.mutantID] = true
    }

    val csvFileName = "accidentsKillingPerScenario.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "accidentsKillingPerScenario",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        killingMatrix.toList().joinToString(
            prefix = "Scenario, ${mutantsKilled.joinToString(",")}\n", separator = "\n") {
                (scenarioUUID, killingList) ->
              "$scenarioUUID,${killingList.toList().joinToString(",") { it.second.toString() }}"
            })
  }
}
