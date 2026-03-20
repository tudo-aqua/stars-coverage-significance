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
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.PlotData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.plots.writeCSVAndTeXFiles
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

  private suspend fun createPlotData(
      metricName: String,
      scenarioFailures: List<ScenarioFailure>,
      repetitions: Int = 500,
      selectedMonitors: Set<MonitorViolation>,
      baseSeed: Long,
      relevantMutants: List<UUID>
  ) {
    val allScenarios = scenarioFailures.map { it.scenarioId }
    val coverageList = List(allScenarios.size) { it + 1 }
    val plotData: Map<Int, PlotData> = coroutineScope {
      coverageList
          .map { coverage ->
            async(Dispatchers.Default) {
              coverage to
                  evaluateCoverage(
                      scenarioFailures = scenarioFailures,
                      allScenarios = allScenarios,
                      coverage = coverage,
                      repetitions = repetitions,
                      selectedMonitors = selectedMonitors,
                      seed = baseSeed * 10_000 + coverage,
                      relevantMutants = relevantMutants)
            }
          }
          .awaitAll()
          .toMap()
    }

    writeCSVAndTeXFiles(
        metricName = metricName,
        map = plotData,
        selectedMonitors = selectedMonitors,
        numberOfMutants = relevantMutants.size)
  }

  private fun evaluateCoverage(
      scenarioFailures: List<ScenarioFailure>,
      allScenarios: List<UUID>,
      coverage: Int,
      repetitions: Int,
      selectedMonitors: Set<MonitorViolation>,
      seed: Long,
      relevantMutants: List<UUID>
  ): PlotData {
    val countOfKilledMutants = MutableList(repetitions) { 0 }
    val countOfFailedMonitors = MutableList(repetitions) { 0 }
    val countOfDistinctMonitorsFailed = MutableList(repetitions) { 0 }

    repeat(repetitions) { repetition ->
      val rng = Random(seed + repetition)

      val repetitionScenarioIds = allScenarios.shuffled(rng).take(coverage)
      val drawnScenarios = scenarioFailures.filter { it.scenarioId in repetitionScenarioIds }

      val drawnScenarioInstances = drawnScenarios.map { it.scenarioInstanceFailures.random(rng) }

      val relevantMonitors =
          drawnScenarioInstances.flatMap { scenarioInstance ->
            scenarioInstance.mutants.filter { mutant ->
              mutant.mutantId in relevantMutants && mutant.violations.any { it in selectedMonitors }
            }
          }

      val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()
      countOfKilledMutants[repetition] = mutantsKilled

      val monitorsFailed = relevantMonitors.flatMap { it.violations }.count()
      countOfFailedMonitors[repetition] = monitorsFailed

      val distinctMonitorsFailed = relevantMonitors.flatMap { it.violations }.toSet().count()
      countOfDistinctMonitorsFailed[repetition] = distinctMonitorsFailed
    }

    return PlotData(
        countOfKilledMutants = countOfKilledMutants,
        countOfFailedMonitors = countOfFailedMonitors,
        countOfDistinctMonitorsFailed = countOfDistinctMonitorsFailed)
  }
}
