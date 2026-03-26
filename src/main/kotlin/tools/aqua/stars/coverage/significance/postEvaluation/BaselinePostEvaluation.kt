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
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.TEST_SUITE_SIZE
import tools.aqua.stars.coverage.significance.distinctMutantIds
import tools.aqua.stars.coverage.significance.failedMonitorMapping
import tools.aqua.stars.coverage.significance.longtailDistribution
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures

object BaselinePostEvaluation {








  val longtail by lazy {
    longtailDistribution
        .map { it.tscInstanceId to it.longTailValue }
        .sortedByDescending { it.second }
  }

  val gridInstances by lazy {
    failedMonitorMapping.flatMap { it.scenarioInstanceFailures }
  }

  val sum by lazy { longtail.sumOf { it.second } }

  val rnd = Random(42L)

  fun evaluate() {
    println("Evaluating mutants killed in random traffic")
    val valuesRandom = evaluateMutantKillingRandom()
    save(valuesRandom, "random")

    println("Evaluating mutants killed in grid-based traffic")
    val valuesGrid = evaluateMutantKillingGrid()
    save(valuesGrid, "grid")
  }

  private fun evaluateMutantKillingRandom(): Pair<List<Int>, List<Int>> {
    val drawnScenarios =
      (0 .. REPETITIONS).map {
        (0..TEST_SUITE_SIZE).map {
          val randomValue = rnd.nextLong(sum)

          val iterator = longtail.iterator()
          var currentElement = iterator.next()
          var currentValue = currentElement.second

          while (currentValue < randomValue && iterator.hasNext()) {
            currentElement = iterator.next()
            currentValue += currentElement.second
          }

          // choose one random instance
          val randomItem =
            failedMonitorMapping.filter { it.scenarioId == currentElement.first }
              .flatMap { it.scenarioInstanceFailures }.random()

          return@map randomItem
        }
      }

    return drawnScenarios.map { evaluateKilling(it) } to
        drawnScenarios.map { evaluateKillingWithMonitors(it) }
  }

  private fun evaluateMutantKillingGrid(): Pair<List<Int>, List<Int>> {
    val drawnScenarios = (0 .. REPETITIONS).map { gridInstances.shuffled(rnd).take(TEST_SUITE_SIZE) }

    return drawnScenarios.map { evaluateKilling(it) } to
        drawnScenarios.map { evaluateKillingWithMonitors(it) }
  }

  private fun evaluateKilling(drawnScenarioFailures: List<ScenarioInstanceFailures>): Int {
    val relevantMonitors =
      drawnScenarioFailures.flatMap { scenarioInstance ->
        scenarioInstance.mutants.filter { mutant ->
          mutant.mutantId in distinctMutantIds && mutant.violations.any()
        }
      }

    val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()

    return mutantsKilled
  }

  private fun evaluateKillingWithMonitors(drawnScenarioFailures: List<ScenarioInstanceFailures>): Int {
    val relevantMonitors =
      drawnScenarioFailures.flatMap { scenarioInstance ->
        scenarioInstance.mutants.filter { mutant ->
          mutant.mutantId in distinctMutantIds && mutant.violations.any()
        }
      }

    val mutantsKilled = relevantMonitors.map { it.mutantId to it.violations }.toSet().count()

    return mutantsKilled
  }

  private fun save(
    values: Pair<List<Int>, List<Int>>,
    identifier: String
  ) {
      val csvFileName = "baseline_${identifier}.csv"
      val path: Path =
          Path.of(
              POST_EVALUATION_BASE_DIR,
              "baseline",
              csvFileName,
          )
      Files.createDirectories(path.parent)

      path.writeText(
          values.first.joinToString(
              prefix = "Coverage, Mutants killed ${identifier}\n", separator = "\n") { it.toString() })

      val csvFileNameWithMonitors = "baseline_with_monitors_${identifier}.csv"
      val pathWithMonitors: Path =
          Path.of(
              POST_EVALUATION_BASE_DIR,
              "baseline_with_monitors",
              csvFileNameWithMonitors,
          )
      Files.createDirectories(pathWithMonitors.parent)

      pathWithMonitors.writeText(
          values.second.joinToString(
              prefix = "Coverage, Mutants killed ${identifier}\n", separator = "\n") { it.toString() })
  }
}
