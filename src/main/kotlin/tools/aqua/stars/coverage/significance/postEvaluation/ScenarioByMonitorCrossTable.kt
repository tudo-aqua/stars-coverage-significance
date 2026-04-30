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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceId

object ScenarioByMonitorCrossTable {
  fun evaluate() {
    println("Starting ScenarioByMonitorCrossTable.")
    Monitors.entries.forEach { monitor -> evaluateForMonitor(monitor) }
    println("Finished ScenarioByMonitorCrossTable.")
  }

  private fun evaluateForMonitor(monitor: Monitors) {
    val mutantsKilledByMonitor =
        distinctMutantFailuresFiltered.filter { it.monitorBitmask and monitor.mask == monitor.mask }
    val mutantsKilled = mutantsKilledByMonitor.map { it.mutantID }.toSet()

    // 160 x 14: Scenario -> Map<MutantID, Killed?>
    val killingMatrix: Map<ScenarioInstanceId, MutableMap<MutantId, Boolean>> =
        longtailDistribution.associate {
          it.tscInstanceId to
              mutantsKilledByMonitor.associate { t -> t.mutantID to false }.toMutableMap()
        }

    mutantsKilledByMonitor.forEach { mutantFailure ->
      killingMatrix[mutantFailure.currentTSCInstance]!![mutantFailure.mutantID] = true
    }

    val csvFileName = "scenarioByMonitorCrossTable_${monitor.name}.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "scenario_by_monitor_cross_table",
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
