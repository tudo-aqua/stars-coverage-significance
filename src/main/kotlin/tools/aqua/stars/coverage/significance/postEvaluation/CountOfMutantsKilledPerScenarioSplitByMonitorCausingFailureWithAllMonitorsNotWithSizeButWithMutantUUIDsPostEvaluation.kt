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

import java.util.UUID
import kotlin.collections.map
import kotlin.collections.sortedByDescending
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.HighwayTrafficScenarioInstanceId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON

object CountOfMutantsKilledPerScenarioSplitByMonitorCausingFailureWithAllMonitorsNotWithSizeButWithMutantUUIDsPostEvaluation {

  fun evaluate(
      allTSCInstances: List<ScenarioIdAndJSON>,
      randomTrafficTSCInstances: List<HighwayTrafficScenarioInstanceId>,
      filteredMutantFailures: List<MutantFailure>
  ) {
    println(
        "Starting CountOfMutantsKilledPerScenarioPostEvaluationSplitByMonitorCausingFailureWithAllMonitorsNotWithSizeButWithUUIDs.")
    val longtail =
        allTSCInstances
            .map { it to randomTrafficTSCInstances.count { t -> t == it.scenarioId } }
            .sortedByDescending { it.second }

    val values: List<Triple<ScenarioIdAndJSON, Int, Map<String, Set<UUID>>>> =
        longtail.map { scenarioAndLongtailCount ->
          val mutantFailuresInScenario =
              filteredMutantFailures.filter {
                it.tscInstance == scenarioAndLongtailCount.first.scenarioId
              }
          Triple(
              scenarioAndLongtailCount.first,
              scenarioAndLongtailCount.second,
              mapOf(
                  "G0Accidents" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 1) == 1 }
                          .map { it.mutantID }
                          .toSet(),
                  "G1SafeDistance" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 2) == 2 }
                          .map { it.mutantID }
                          .toSet(),
                  "G2UnnecessaryBraking" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 4) == 4 }
                          .map { it.mutantID }
                          .toSet(),
                  "G3MaximumSpeed" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 8) == 8 }
                          .map { it.mutantID }
                          .toSet(),
                  "G4TrafficFlow" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 16) == 16 }
                          .map { it.mutantID }
                          .toSet(),
                  "G5EmergencyBraking" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 32) == 32 }
                          .map { it.mutantID }
                          .toSet(),
                  "I1Stopping" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 64) == 64 }
                          .map { it.mutantID }
                          .toSet(),
                  "I2FasterThanLeftTraffic" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 128) == 128 }
                          .map { it.mutantID }
                          .toSet(),
                  "I3DangerousCutin" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 256) == 256 }
                          .map { it.mutantID }
                          .toSet()))
        }
  }
}
