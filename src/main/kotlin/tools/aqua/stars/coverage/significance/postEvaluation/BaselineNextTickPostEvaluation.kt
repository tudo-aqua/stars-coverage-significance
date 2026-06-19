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
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.tickWiseNextTickMonitorViolations

/**
 * Post-evaluation that simulates test suites by sampling individual ticks directly from
 * [MetricFailedMonitorsTable] and measuring how many distinct mutants are killed, i.e. have at
 * least one tick in the sample where [MetricFailedMonitorsTable.nextTickMonitorG0Failed] is `true`.
 *
 * Three sampling strategies are planned:
 * 1. Uniform random from the full table (implemented).
 * 2. TSC-instance-stratified sampling (planned).
 * 3. Decision-tree-leaf-stratified sampling (planned).
 */
object BaselineNextTickPostEvaluation {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "baseline_next_tick")

  private val rnd = Random(42L)

  /**
   * All ticks grouped by their TSC instance ID. Used by [evaluateRoundRobin] for TSC-stratified
   * sampling.
   */
  private val tscGroups: List<List<NextTickPostEvaluationDatabaseEntry>> by lazy {
    tickWiseNextTickMonitorViolations.groupBy { it.tscInstanceId }.values.toList()
  }

  /**
   * All ticks that have a leaf-node annotation, grouped by
   * [NextTickPostEvaluationDatabaseEntry.leafNodeId]. Used by [evaluateRoundRobin] for
   * decision-tree-leaf-stratified sampling.
   */
  private val leafGroups: List<List<NextTickPostEvaluationDatabaseEntry>> by lazy {
    tickWiseNextTickMonitorViolations
        .filter { it.leafNodeId != null }
        .groupBy { it.leafNodeId }
        .values
        .toList()
  }

  /** Runs all sampling strategies on the full tick dataset and saves the results. */
  fun evaluate() {
    println("Starting BaselineNextTickPostEvaluation.")

    println("  Evaluating uniform-random tick sampling.")
    save(evaluateRandomDraw(tickWiseNextTickMonitorViolations), "random")

    println("  Evaluating TSC-instance-stratified tick sampling.")
    save(evaluateRoundRobin(tscGroups), "tsc")

    println("  Evaluating decision-tree-leaf-stratified tick sampling.")
    save(evaluateRoundRobin(leafGroups), "leaf")

    println("Finished BaselineNextTickPostEvaluation.")
  }

  /**
   * Loads the test-set mutant IDs from the most recent decision tree run, restricts the tick
   * dataset to those mutants, and then runs all three sampling strategies on the filtered subset.
   * Results are saved with a `_split` suffix to distinguish them from the full-dataset results.
   *
   * Does nothing if no decision tree run has been recorded in the database yet.
   */
  fun evaluateSplit() {
    println("Starting BaselineNextTickPostEvaluation (split mode).")

    val testMutantIds = db { DecisionTreeRunsRepository.getLatestRunTestMutantIds() }
    if (testMutantIds.isNullOrEmpty()) {
      println(
          "  No decision tree run with a test set of mutants found in database. Skipping split evaluation.")
      return
    }
    println("  Loaded ${testMutantIds.size} test-set mutant IDs from latest run.")

    val testMutantIdSet = testMutantIds.toHashSet()
    val filteredTicks = tickWiseNextTickMonitorViolations.filter { it.mutantId in testMutantIdSet }
    println("  Filtered to ${filteredTicks.size} ticks for test-set mutants.")

    val filteredTscGroups = filteredTicks.groupBy { it.tscInstanceId }.values.toList()
    val filteredLeafGroups =
        filteredTicks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }.values.toList()

    println("  Evaluating uniform-random tick sampling (split).")
    save(evaluateRandomDraw(filteredTicks), "random_split")

    println("  Evaluating TSC-instance-stratified tick sampling (split).")
    save(evaluateRoundRobin(filteredTscGroups), "tsc_split")

    println("  Evaluating decision-tree-leaf-stratified tick sampling (split).")
    save(evaluateRoundRobin(filteredLeafGroups), "leaf_split")

    println("Finished BaselineNextTickPostEvaluation (split mode).")
  }

  /**
   * Draws [TEST_SUITE_SIZE] ticks uniformly at random (with replacement) from [pool], [REPETITIONS]
   * times. Each repetition counts the number of distinct mutant IDs for which
   * [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true` in the sample.
   *
   * @param pool Tick entries to sample from.
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRandomDraw(pool: List<NextTickPostEvaluationDatabaseEntry>): List<Int> =
      (0..REPETITIONS).map {
        (0 until TEST_SUITE_SIZE)
            .map { pool.random(rnd) }
            .filter { it.nextTickG0Failed == true }
            .map { it.mutantId }
            .toSet()
            .size
      }

  /**
   * Builds a test suite of [TEST_SUITE_SIZE] ticks by cycling round-robin through [groups], drawing
   * one tick per group per cycle. Each group is shuffled independently before each repetition. When
   * a group is exhausted it wraps around (rotation). Repeats [REPETITIONS] times and counts unique
   * mutant IDs for which [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true` in each
   * suite.
   *
   * @param groups Pre-partitioned tick lists (e.g., by TSC instance or leaf node).
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRoundRobin(
      groups: List<List<NextTickPostEvaluationDatabaseEntry>>
  ): List<Int> =
      (0..REPETITIONS).map {
        val mutableGroups = groups.map { it.shuffled(rnd).toMutableList() }
        val testSuite = mutableListOf<NextTickPostEvaluationDatabaseEntry>()
        var i = 0
        while (testSuite.size < TEST_SUITE_SIZE) {
          val group = mutableGroups[i % mutableGroups.size]
          val tick = group.removeFirst()
          group.add(tick)
          testSuite.add(tick)
          i++
        }
        testSuite.filter { it.nextTickG0Failed == true }.map { it.mutantId }.toSet().size
      }

  /**
   * Saves [results] as a single-column CSV under [BASE_PATH].
   *
   * @param results One killed-mutant count per repetition.
   * @param identifier Sampling strategy label used in the file name (e.g. `"random"`).
   */
  private fun save(results: List<Int>, identifier: String) {
    val path = BASE_PATH.resolve("baseline_next_tick_${identifier}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(
            prefix = "Mutants killed ${identifier}\n",
            separator = "\n",
        ) {
          it.toString()
        })
    println("  CSV written to: $path")
  }
}
