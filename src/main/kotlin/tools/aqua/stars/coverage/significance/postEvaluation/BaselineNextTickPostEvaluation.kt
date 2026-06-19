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
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
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

  /**
   * Runs all sampling strategies on the full tick dataset for every suite size in
   * [NEXT_TICK_SUITE_SIZES]. Results are written to `baseline_next_tick/size_<n>/`.
   */
  fun evaluate() {
    println("Starting BaselineNextTickPostEvaluation.")

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize:")

      println("    Evaluating uniform-random tick sampling.")
      save(evaluateRandomDraw(tickWiseNextTickMonitorViolations, suiteSize), "random", suiteSize)

      println("    Evaluating TSC-instance-stratified tick sampling.")
      save(evaluateRoundRobin(tscGroups, suiteSize), "tsc", suiteSize)

      println("    Evaluating decision-tree-leaf-stratified tick sampling.")
      save(evaluateRoundRobin(leafGroups, suiteSize), "leaf", suiteSize)
    }

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

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (split):")

      println("    Evaluating uniform-random tick sampling (split).")
      save(evaluateRandomDraw(filteredTicks, suiteSize), "random_split", suiteSize)

      println("    Evaluating TSC-instance-stratified tick sampling (split).")
      save(evaluateRoundRobin(filteredTscGroups, suiteSize), "tsc_split", suiteSize)

      println("    Evaluating decision-tree-leaf-stratified tick sampling (split).")
      save(evaluateRoundRobin(filteredLeafGroups, suiteSize), "leaf_split", suiteSize)
    }

    println("Finished BaselineNextTickPostEvaluation (split mode).")
  }

  /**
   * Draws [suiteSize] ticks uniformly at random (with replacement) from [pool], [REPETITIONS]
   * times. Each repetition counts the number of distinct mutant IDs for which
   * [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true` in the sample.
   *
   * @param pool Tick entries to sample from.
   * @param suiteSize Number of ticks to draw per repetition.
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRandomDraw(
      pool: List<NextTickPostEvaluationDatabaseEntry>,
      suiteSize: Int,
  ): List<Int> =
      (0..REPETITIONS).map {
        (0 until suiteSize)
            .map { pool.random(rnd) }
            .filter { it.nextTickG0Failed == true }
            .map { it.mutantId }
            .toSet()
            .size
      }

  /**
   * Builds a test suite of [suiteSize] ticks by cycling round-robin through [groups], drawing one
   * tick per group per cycle. Each group is shuffled independently before each repetition. When a
   * group is exhausted it wraps around (rotation). Repeats [REPETITIONS] times and counts unique
   * mutant IDs for which [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true` in each
   * suite.
   *
   * @param groups Pre-partitioned tick lists (e.g., by TSC instance or leaf node).
   * @param suiteSize Number of ticks to include in each test suite.
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRoundRobin(
      groups: List<List<NextTickPostEvaluationDatabaseEntry>>,
      suiteSize: Int,
  ): List<Int> =
      (0..REPETITIONS).map {
        val mutableGroups = groups.map { it.shuffled(rnd).toMutableList() }
        val testSuite = mutableListOf<NextTickPostEvaluationDatabaseEntry>()
        var i = 0
        while (testSuite.size < suiteSize) {
          val group = mutableGroups[i % mutableGroups.size]
          val tick = group.removeFirst()
          group.add(tick)
          testSuite.add(tick)
          i++
        }
        testSuite.filter { it.nextTickG0Failed == true }.map { it.mutantId }.toSet().size
      }

  /**
   * Saves [results] as a single-column CSV under `[BASE_PATH]/size_<suiteSize>/`.
   *
   * @param results One killed-mutant count per repetition.
   * @param identifier Sampling strategy label used in the file name (e.g. `"random"`).
   * @param suiteSize Suite size used for this evaluation run, determines the subfolder.
   */
  private fun save(results: List<Int>, identifier: String, suiteSize: Int) {
    val path = BASE_PATH.resolve("size_$suiteSize/baseline_next_tick_${identifier}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(
            prefix = "Mutants killed ${identifier}\n",
            separator = "\n",
        ) {
          it.toString()
        })
    println("    CSV written to: $path")
  }
}
