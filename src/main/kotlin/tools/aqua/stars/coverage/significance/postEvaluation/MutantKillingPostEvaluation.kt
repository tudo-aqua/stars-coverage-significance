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
import tools.aqua.stars.coverage.significance.distinctMutantIds
import tools.aqua.stars.coverage.significance.failedMonitorMapping
import tools.aqua.stars.coverage.significance.monitorCombinations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.PlotData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.plots.toFileNameSuffix
import tools.aqua.stars.coverage.significance.postEvaluation.plots.writeCSVAndTeXFiles
import tools.aqua.stars.coverage.significance.tsc

object MutantKillingPostEvaluation {

  val TSC_SIZE = tsc().instanceCount.toInt()
  val REPETITIONS: List<Int> = listOf(1, 2, 4, 8, 16) * TSC_SIZE

  typealias Repetitions = Int

  typealias Coverage = Int

  fun evaluate() {
    println("Starting MutantKillingPostEvaluation.")
    monitorCombinations.mapIndexed { index, monitorCombination ->
      println("Evaluating monitor combination: ${monitorCombination.toFileNameSuffix()}")
      createPlotData(
          scenarioFailures = failedMonitorMapping,
          selectedMonitors = monitorCombination,
          baseSeed = 42L + index,
          relevantMutants = distinctMutantIds)
    }

    println("Finished MutantKillingPostEvaluation.")
  }

  private fun createPlotData(
      scenarioFailures: List<ScenarioFailure>,
      selectedMonitors: Set<MonitorViolation>,
      baseSeed: Long,
      relevantMutants: List<UUID>
  ) {
    print("Filtering scenarios...")
    val allScenarios = scenarioFailures.map { it.scenarioId }
    println("Finished.")

    val coverageList = List(allScenarios.size) { it + 1 }
    val plotData: Map<Repetitions, MutableMap<Coverage, PlotData>> =
        REPETITIONS.associateWith { mutableMapOf() }

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
                      seed = baseSeed * 10_000 + coverage,
                      relevantMutants = relevantMutants)

              REPETITIONS.forEach { repetition ->
                plotData[repetition]!![coverage] = values[repetition]!!
              }
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
      seed: Long,
      relevantMutants: List<UUID>
  ): Map<Repetitions, PlotData> {
    val countOfKilledMutants = REPETITIONS.associateWith { MutableList(it) { 0 } }
    val countOfMutantsKilledWithMonitors = REPETITIONS.associateWith { MutableList(it) { 0 } }
    val countOfFailedMonitors = REPETITIONS.associateWith { MutableList(it) { 0 } }
    val countOfDistinctMonitorsFailed = REPETITIONS.associateWith { MutableList(it) { 0 } }

    repeat(REPETITIONS.last()) { repetition ->
      val rng = Random(seed + repetition)

      // Select #coverage many random scenarioIDs
      val repetitionScenarioIds = allScenarios.shuffled(rng).take(coverage)

      // Filter all failures for the selected
      val drawnScenarios = scenarioFailures.filter { it.scenarioId in repetitionScenarioIds }

      // From each scenarioID draw one random instance
      val drawnScenarioInstances = drawnScenarios.map { it.scenarioInstanceFailures.random(rng) }

      val relevantMonitors =
          drawnScenarioInstances.flatMap { scenarioInstance ->
            scenarioInstance.mutants.filter { mutant ->
              mutant.mutantId in relevantMutants && mutant.violations.any { it in selectedMonitors }
            }
          }

      val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()
      val mutantsKilledWithMonitors =
          relevantMonitors.map { it.mutantId to it.violations }.toSet().count()
      val monitorsFailed = relevantMonitors.flatMap { it.violations }.count()
      val distinctMonitorsFailed = relevantMonitors.flatMap { it.violations }.toSet().count()

      REPETITIONS.filter { repetition < it }
          .forEach {
            countOfKilledMutants[it]!![repetition] = mutantsKilled
            countOfMutantsKilledWithMonitors[it]!![repetition] = mutantsKilledWithMonitors
            countOfFailedMonitors[it]!![repetition] = monitorsFailed
            countOfDistinctMonitorsFailed[it]!![repetition] = distinctMonitorsFailed
          }
    }

    return REPETITIONS.associateWith {
      PlotData(
          countOfKilledMutants = countOfKilledMutants[it]!!,
          countOfMutantsKilledWithMonitors = countOfMutantsKilledWithMonitors[it]!!,
          countOfFailedMonitors = countOfFailedMonitors[it]!!,
          countOfDistinctMonitorsFailed = countOfDistinctMonitorsFailed[it]!!)
    }
  }

  private operator fun List<Int>.times(other: Int) = this.map { it * other }
}
