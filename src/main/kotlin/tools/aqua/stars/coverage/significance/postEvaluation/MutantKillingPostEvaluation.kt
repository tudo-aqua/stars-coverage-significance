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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.plots.createPlotData
import tools.aqua.stars.coverage.significance.toFileNameSuffix

object MutantKillingPostEvaluation {

  fun evaluate(
      failedMonitorMapping: List<ScenarioFailure>,
      monitorCombinations: List<Set<MonitorViolation>>,
      mutantIds: List<UUID>,
      identifier: String
  ) = runBlocking {
    println("Starting MutantKillingPostEvaluation -$identifier.")
    monitorCombinations
        .mapIndexed { index, monitorCombination ->
          async(Dispatchers.Default) {
            println("Evaluating monitor combination: ${monitorCombination.toFileNameSuffix()}")
            createPlotData(
                metricName = identifier,
                scenarioFailures = failedMonitorMapping,
                selectedMonitors = monitorCombination,
                baseSeed = 42L + index,
                relevantMutants = mutantIds)
          }
        }
        .awaitAll()

    println("Finished MutantKillingPostEvaluation.")
    return@runBlocking failedMonitorMapping
  }
}
