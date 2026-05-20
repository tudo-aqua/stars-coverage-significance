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
import kotlinx.coroutines.runBlocking
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.TEST_SUITE_SIZE
import tools.aqua.stars.coverage.significance.distinctMutantIds
import tools.aqua.stars.coverage.significance.failedMonitorMapping
import tools.aqua.stars.coverage.significance.monitorCombinations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.PlotData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures
import tools.aqua.stars.coverage.significance.postEvaluation.plots.toFileNameSuffix
import tools.aqua.stars.coverage.significance.postEvaluation.plots.writeCSVAndTeXFiles
import tools.aqua.stars.coverage.significance.utils.MonitorViolation

/** Post-evaluation for the mutant killing metric. */
object MutantKillingPostEvaluation {

  typealias Coverage = Int

  /** Evaluates the mutant killing metric. */
  fun evaluate() {
    println("Starting MutantKillingPostEvaluation.")


    createPlotData(
        scenarioFailures = failedMonitorMapping,
        selectedMonitors = setOf(MonitorViolation.G0Accidents, MonitorViolation.G1SafeDistance, MonitorViolation.G2EmergencyBraking, MonitorViolation.G4TrafficFlow, MonitorViolation.I2FasterThanLeftTraffic),
        baseSeed = 42L)


    println("Finished MutantKillingPostEvaluation.")
  }

  private fun createPlotData(
      scenarioFailures: List<ScenarioFailure>,
      selectedMonitors: Set<MonitorViolation>,
      baseSeed: Long
  ) {
    print("Filtering scenarios...")
    val allScenarios = scenarioFailures.map { it.scenarioId }
    println("Finished.")

    val coverageList = List(allScenarios.size) { it + 1 }
    val plotData: MutableMap<Coverage, PlotData> = mutableMapOf()

    runBlocking {
      coverageList
          .map { coverage ->
            async(Dispatchers.Default) {
              print("\r$coverage/${allScenarios.size}")
              val values =
                  evaluateCoverage(
                      scenarioFailures = scenarioFailures,
                      allScenarios = allScenarios,
                      coverage = coverage,
                      selectedMonitors = selectedMonitors,
                      seed = baseSeed * 10_000 + coverage)

                plotData[coverage] = values
            }
          }
          .awaitAll()
    }
    println()

    writeCSVAndTeXFiles(
        metricName = "mutant_killing", map = plotData, selectedMonitors = selectedMonitors)
  }

  private fun evaluateCoverage(
      scenarioFailures: List<ScenarioFailure>,
      allScenarios: List<UUID>,
      coverage: Int,
      selectedMonitors: Set<MonitorViolation>,
      seed: Long
  ): PlotData {
    val countOfKilledMutants = MutableList(REPETITIONS) { 0 }
    val countOfMutantsKilledWithMonitors = MutableList(REPETITIONS) { 0 }
    val countOfFailedMonitors = MutableList(REPETITIONS) { 0 }
    val countOfDistinctMonitorsFailed = MutableList(REPETITIONS) { 0 }

    repeat(REPETITIONS) { repetition ->
      val rng = Random(seed + repetition)

      // Select #coverage many random scenarioIDs
      val repetitionScenarioIds = allScenarios.shuffled(rng).take(coverage)

      // Filter all failures for the selected
      val drawnScenarios = scenarioFailures.filter { it.scenarioId in repetitionScenarioIds }.map { it.scenarioInstanceFailures.shuffled(rng).toMutableList() }

      // From each scenarioID draw one random instance
//      val drawnScenarioInstances = drawnScenarios.map { it.scenarioInstanceFailures.random(rng) }
      val testSuite = mutableListOf< ScenarioInstanceFailures>()
      for (i in 0 .. TEST_SUITE_SIZE) {
        val drawnScenario = drawnScenarios[i % drawnScenarios.size].removeFirst()
        drawnScenarios[i % drawnScenarios.size] += drawnScenario
        testSuite += drawnScenario
      }

      val relevantMonitors =
        testSuite.flatMap { scenarioInstance ->
            scenarioInstance.mutants.filter { mutant ->
              mutant.mutantId in distinctMutantIds &&
                  mutant.violations.any { it in selectedMonitors }
            }
          }

      val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()
      val mutantsKilledWithMonitors =
          relevantMonitors.map { it.mutantId to it.violations }.toSet().count()
      val monitorsFailed = relevantMonitors.flatMap { it.violations }.count()
      val distinctMonitorsFailed = relevantMonitors.flatMap { it.violations }.toSet().count()


      countOfKilledMutants[repetition] = mutantsKilled
      countOfMutantsKilledWithMonitors[repetition] = mutantsKilledWithMonitors
      countOfFailedMonitors[repetition] = monitorsFailed
      countOfDistinctMonitorsFailed[repetition] = distinctMonitorsFailed
    }

    return PlotData(
          countOfKilledMutants = countOfKilledMutants,
          countOfMutantsKilledWithMonitors = countOfMutantsKilledWithMonitors,
          countOfFailedMonitors = countOfFailedMonitors,
          countOfDistinctMonitorsFailed = countOfDistinctMonitorsFailed)
  }
}
