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
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.TEST_SUITE_SIZE
import tools.aqua.stars.coverage.significance.distinctMutantIds
import tools.aqua.stars.coverage.significance.failedMonitorMapping
import tools.aqua.stars.coverage.significance.failedMonitorMappingByLeaf
import tools.aqua.stars.coverage.significance.longtailDistribution
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures

/**
 * Object responsible for performing post-evaluation of baseline data, particularly focusing on the
 * evaluation of mutant killing in simulated traffic scenarios. This includes operations for
 * analyzing both random and grid-based traffic distributions.
 */
object BaselinePostEvaluation {

  /** The longtail distribution of the TSC instances. */
  val longtail by lazy {
    longtailDistribution
        .map { it.tscInstanceId to it.longTailValue }
        .sortedByDescending { it.second }
  }

  /** All scenario instances, used for uniform random sampling. */
  val gridInstances by lazy { failedMonitorMapping.flatMap { it.scenarioInstanceFailures } }

  /**
   * Scenario instances grouped by decision-tree leaf node ID.
   *
   * Each group contains the scenario instances that have at least one tick classified into that
   * leaf. Used by [evaluateMutantKillingLeaf] to build a leaf-stratified test suite.
   */
  val leafGroups by lazy { failedMonitorMappingByLeaf }

  /** The sum of longtail values. */
  val sum by lazy { longtail.sumOf { it.second } }

  /** The random number generator used for sampling. */
  val rnd = Random(42L)

  /**
   * Evaluates the mutant killing in simulated traffic scenarios. This includes random traffic,
   * grid-based traffic, and leaf-node-stratified traffic distributions.
   */
  fun evaluate() {
    println("Evaluating mutants killed in random traffic")
    val valuesRandom = evaluateMutantKillingRandom()
    save(valuesRandom, "random")

    println("Evaluating mutants killed in grid-based traffic")
    val valuesGrid = evaluateMutantKillingGrid()
    save(valuesGrid, "grid")

    println("Evaluating mutants killed in leaf-node-stratified traffic")
    val valuesLeaf = evaluateMutantKillingLeaf()
    save(valuesLeaf, "leaf")
  }

  /**
   * Evaluates the mutant killing in simulated traffic scenarios using a random distribution.
   *
   * @return Pair of lists: first list contains the number of mutants killed in each scenario,
   *   second list contains the number of mutants killed in each scenario with monitors.
   */
  private fun evaluateMutantKillingRandom(): Pair<List<Int>, List<Int>> {
    val drawnScenarios =
        (0..REPETITIONS).map {
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
                failedMonitorMapping
                    .filter { it.scenarioId == currentElement.first }
                    .flatMap { it.scenarioInstanceFailures }
                    .random()

            return@map randomItem
          }
        }

    return drawnScenarios.map { evaluateKilling(it) } to
        drawnScenarios.map { evaluateKillingWithMonitors(it) }
  }

  /**
   * Evaluates the mutant killing in simulated traffic scenarios using a grid-based distribution.
   *
   * @return Pair of lists: first list contains the number of mutants killed in each scenario,
   *   second list contains the number of mutants killed in each scenario with monitors.
   */
  private fun evaluateMutantKillingGrid(): Pair<List<Int>, List<Int>> {
    val drawnScenarios = (0..REPETITIONS).map { gridInstances.shuffled(rnd).take(TEST_SUITE_SIZE) }

    return drawnScenarios.map { evaluateKilling(it) } to
        drawnScenarios.map { evaluateKillingWithMonitors(it) }
  }

  /**
   * Evaluates the mutant killing in simulated traffic scenarios using leaf-node stratification.
   *
   * For each repetition a test suite of [TEST_SUITE_SIZE] is built by cycling through the leaf node
   * groups in order and drawing one scenario instance per leaf per cycle. This ensures the test
   * suite is spread across all decision-tree leaf categories rather than being drawn uniformly or
   * weighted by longtail frequency.
   *
   * @return Pair of lists: first list contains the number of mutants killed in each repetition,
   *   second list contains the number of mutants killed including monitor differentiation.
   */
  private fun evaluateMutantKillingLeaf(): Pair<List<Int>, List<Int>> {
    val drawnScenarios =
        (0..REPETITIONS).map {
          val groups = leafGroups.map { it.scenarioInstanceFailures.shuffled(rnd).toMutableList() }
          val testSuite = mutableListOf<ScenarioInstanceFailures>()
          var i = 0
          while (testSuite.size < TEST_SUITE_SIZE) {
            val group = groups[i % groups.size]
            val instance = group.removeFirst()
            group.add(instance) // rotate so every instance is eventually reused
            testSuite.add(instance)
            i++
          }
          testSuite
        }

    return drawnScenarios.map { evaluateKilling(it) } to
        drawnScenarios.map { evaluateKillingWithMonitors(it) }
  }

  /**
   * Evaluates the mutant killing in a given list of scenario failures.
   *
   * @param drawnScenarioFailures List of scenario failures to evaluate.
   * @return Number of mutants killed in the given list of scenario failures.
   */
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

  /**
   * Evaluates the mutant killing in a given list of scenario failures, including monitors.
   *
   * @param drawnScenarioFailures List of scenario failures to evaluate.
   * @return Number of mutants killed in the given list of scenario failures, including monitors.
   */
  private fun evaluateKillingWithMonitors(
      drawnScenarioFailures: List<ScenarioInstanceFailures>
  ): Int {
    val relevantMonitors =
        drawnScenarioFailures.flatMap { scenarioInstance ->
          scenarioInstance.mutants.filter { mutant ->
            mutant.mutantId in distinctMutantIds && mutant.violations.any()
          }
        }

    val mutantsKilled = relevantMonitors.map { it.mutantId to it.violations }.toSet().count()

    return mutantsKilled
  }

  /**
   * Saves the evaluation results to CSV files.
   *
   * @param values Pair of lists: first list contains the number of mutants killed in each scenario,
   *   second list contains the number of mutants killed in each scenario with monitors.
   * @param identifier Identifier for the evaluation (e.g., "random", "grid").
   */
  private fun save(values: Pair<List<Int>, List<Int>>, identifier: String) {
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
            prefix = "Coverage, Mutants killed ${identifier}\n", separator = "\n") {
              it.toString()
            })

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
            prefix = "Coverage, Mutants killed ${identifier}\n", separator = "\n") {
              it.toString()
            })
  }
}
