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
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

object ScenarioByScenarioCrossTable {

  fun evaluate(filteredMutantFailures: List<MutantFailure>, scenarioIds: List<UUID>) {
    val consoleProgress = ConsoleProgress(total = scenarioIds.size * scenarioIds.size)
    println("ScenarioByScenarioCrossTable - Starting")
    val heatmap =
        Array(scenarioIds.size) { Array<Triple<UUID, UUID, Int>?>(scenarioIds.size) { null } }

    scenarioIds.forEachIndexed { outerIndex, outerScenarioId ->
      val distinctMutantsKilledByOuterScenario =
          filteredMutantFailures
              .filter { it.startingScenario == outerScenarioId }
              .map { it.mutantID }
              .toSet()

      scenarioIds.forEachIndexed { innerIndex, innerScenarioId ->
        consoleProgress.step("Running scenario $outerScenarioId in $innerScenarioId")
        val distinctMutantsKilledByInnerScenario =
            filteredMutantFailures
                .filter { it.startingScenario == innerScenarioId }
                .map { it.mutantID }
                .toSet()

        val difference = distinctMutantsKilledByOuterScenario - distinctMutantsKilledByInnerScenario
        heatmap[outerIndex][innerIndex] = Triple(outerScenarioId, innerScenarioId, difference.size)
      }
    }
    heatmap.sortBy { row -> row.sumOf { it!!.third } }
    writeCSVFile(scenarioIds, heatmap)
  }

  private fun writeCSVFile(
      scenarioIds: List<UUID>,
      heatmap: Array<Array<Triple<UUID, UUID, Int>?>>
  ) {
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "scenario_by_scenario_cross_table",
            "scenario_by_scenario_cross_table.csv",
        )
    Files.createDirectories(path.parent)
    val csvString =
        heatmap.joinToString(
            prefix = "x, ${scenarioIds.joinToString(separator = ",") { it.toString() }}\n",
            separator = "\n") { row ->
              row.joinToString(prefix = "${row.first()!!.first},", separator = ",") {
                "${it!!.third}"
              }
            }
    path.writeText(csvString)
  }
}
