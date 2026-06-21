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
import java.util.stream.Collectors
import kotlin.io.path.writeText
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry

/**
 * Post-evaluation that simulates test suites by sampling individual ticks directly from
 * [MetricFailedMonitorsTable] and measuring how many distinct mutants are killed, i.e. have at
 * least one tick in the sample where [MetricFailedMonitorsTable.nextTickMonitorG0Failed] is `true`.
 *
 * Three sampling strategies are evaluated:
 * 1. Uniform random from the full table.
 * 2. TSC-instance-stratified round-robin sampling.
 * 3. Decision-tree-leaf-stratified round-robin sampling.
 *
 * All tick data is loaded from the database exactly once per evaluation call, then all groupings
 * and repetitions operate on the in-memory list.
 */
object BaselineNextTickPostEvaluation {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "baseline_next_tick")

  /**
   * Runs all sampling strategies on the full tick dataset for every suite size in
   * [NEXT_TICK_SUITE_SIZES]. Results are written to `baseline_next_tick/size_<n>/`.
   *
   * Tick data is loaded once (with leaf assignments from the most recent full run where
   * `train_fraction = 1.0`). All groupings are derived from that single in-memory list. If no full
   * run exists, leaf assignments are taken from the most recent run of any fraction, and the leaf
   * strategy is skipped.
   */
  fun evaluate() {
    println("Starting BaselineNextTickPostEvaluation.")

    val fullRunId = db { DecisionTreeRunsRepository.getLatestFullRunId() }
    if (fullRunId != null) {
      println("  Using leaf assignments from full run ${fullRunId.value}.")
    } else {
      println("  No full run (train_fraction=1.0) found — leaf strategy will be skipped.")
    }

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations(forRunId = fullRunId) }
    println("  Loaded ${allTicks.size} ticks.")

    println("  Grouping ticks by TSC instance...")
    val tscGroups = allTicks.groupBy { it.tscInstanceId }.values.toList()

    val leafGroups: List<List<NextTickPostEvaluationDatabaseEntry>> =
        if (fullRunId != null) {
          println("  Grouping ticks by leaf node...")
          allTicks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }.values.toList()
        } else {
          emptyList()
        }

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize:")

      println("    Evaluating uniform-random tick sampling.")
      save(evaluateRandomDraw(allTicks, suiteSize), "random", suiteSize)

      println("    Evaluating TSC-instance-stratified tick sampling.")
      save(evaluateRoundRobin(tscGroups, suiteSize), "tsc", suiteSize)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating decision-tree-leaf-stratified tick sampling.")
        save(evaluateRoundRobin(leafGroups, suiteSize), "leaf", suiteSize)
      }
    }

    println("Finished BaselineNextTickPostEvaluation.")
  }

  /**
   * Loads the test-set mutant IDs from the most recent decision tree run, restricts the tick
   * dataset to those mutants, and then runs all three sampling strategies on the filtered subset.
   * Results are saved with a `_split` suffix to distinguish them from the full-dataset results.
   *
   * Tick data (with leaf assignments from the most recent run) is loaded once and filtered
   * in-memory. Does nothing if no decision tree run has been recorded in the database yet.
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

    println("  Loading tick data into memory (this may take several minutes)...")
    // forRunId = null → uses leaf assignments from the most recent run (the split run).
    val allTicks = db { buildTickWiseNextTickMonitorViolations() }
    println("  Loaded ${allTicks.size} ticks.")

    val testMutantIdSet = testMutantIds.toHashSet()
    val filteredTicks = allTicks.filter { it.mutantId in testMutantIdSet }
    println("  Filtered to ${filteredTicks.size} ticks for test-set mutants.")

    println("  Grouping ticks by TSC instance...")
    val filteredTscGroups = filteredTicks.groupBy { it.tscInstanceId }.values.toList()
    println("  Grouping ticks by leaf node...")
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
   * Repetitions run in parallel; each uses a deterministic seed derived from its index so results
   * are reproducible regardless of thread scheduling.
   *
   * @param pool Tick entries to sample from.
   * @param suiteSize Number of ticks to draw per repetition.
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRandomDraw(
      pool: List<NextTickPostEvaluationDatabaseEntry>,
      suiteSize: Int,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val killed = HashSet<Int>()
            repeat(suiteSize) {
              val entry = pool[rng.nextInt(pool.size)]
              if (entry.nextTickG0Failed == true) killed.add(entry.mutantId)
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Builds a test suite of [suiteSize] ticks by cycling round-robin through [groups], drawing one
   * tick uniformly at random from the current group at each slot. Repeats [REPETITIONS] times and
   * counts unique mutant IDs for which [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is
   * `true` in each suite.
   *
   * Direct random indexing replaces the former shuffle+rotate pattern, avoiding O(pool_size)
   * allocations per repetition. Repetitions run in parallel.
   *
   * @param groups Pre-partitioned tick lists (e.g., by TSC instance or leaf node).
   * @param suiteSize Number of ticks to include in each test suite.
   * @return One killed-mutant count per repetition.
   */
  private fun evaluateRoundRobin(
      groups: List<List<NextTickPostEvaluationDatabaseEntry>>,
      suiteSize: Int,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val killed = HashSet<Int>()
            repeat(suiteSize) { slot ->
              val group = groups[slot % groups.size]
              val entry = group[rng.nextInt(group.size)]
              if (entry.nextTickG0Failed == true) killed.add(entry.mutantId)
            }
            killed.size
          }
          .collect(Collectors.toList())

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
