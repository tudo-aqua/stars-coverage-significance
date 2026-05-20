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
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.distinctMutantFailuresFiltered
import tools.aqua.stars.coverage.significance.longtailDistribution
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON

object MutantsKilledByMonitorsPerScenario {
  fun evaluate() {
    println("Starting MutantsKilledByMonitorsPerScenario.")

    val values: List<Triple<ScenarioIdAndJSON, Int, Map<String, Int>>> =
        longtailDistribution.map { scenarioAndLongtailCount ->
          val mutantFailuresInScenario =
              distinctMutantFailuresFiltered.filter {
                it.currentTSCInstance == scenarioAndLongtailCount.tscInstanceId
              }
          Triple(
              ScenarioIdAndJSON(
                  scenarioAndLongtailCount.tscInstanceId, scenarioAndLongtailCount.tscInstanceJson),
              scenarioAndLongtailCount.longTailValue.toInt(),
              Monitors.entries.associate { monitor ->
                monitor.name to
                    mutantFailuresInScenario
                        .filter { (it.monitorBitmask and monitor.mask) == monitor.mask }
                        .map { it.mutantID }
                        .toSet()
                        .size
              })
        }

    val csvFileName = "mutantsKilledByMonitorsPerScenario.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "mutants_killed_by_monitors_per_scenario",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        values.joinToString(
            prefix =
                "Scenario, Frequency in longtail, ${Monitors.entries.joinToString(",") { it.name }}\n",
            separator = "\n") {
              "${it.first.scenarioInstanceId},${it.second},${Monitors.entries.joinToString(",") { m -> "${it.third[m.name]}" }}"
            })
    println("Finished MutantsKilledByMonitorsPerScenario.")
  }
}
