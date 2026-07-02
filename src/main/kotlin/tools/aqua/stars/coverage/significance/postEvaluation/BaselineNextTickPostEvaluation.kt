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
import tools.aqua.stars.coverage.significance.MAX_RARE_MUTANT_FAILURES
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioG0Violation
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioG0ViolationsView
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.StartingScenarioId

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

    val testMutantIdSet = testMutantIds.toSet()
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
   * Draws up to [suiteSize] ticks uniformly at random **without replacement** from [pool],
   * [REPETITIONS] times. Each repetition counts the number of distinct mutant IDs for which
   * [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true` in the sample.
   *
   * If the pool is smaller than [suiteSize] all items are drawn and the loop stops early. A `Set`
   * of used indices avoids copying the pool and runs in O(1) expected time per draw when `suiteSize
   * ≪ pool.size`. Repetitions run in parallel with deterministic per-rep seeds.
   *
   * @param pool Tick entries to sample from.
   * @param suiteSize Maximum number of ticks to draw per repetition.
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
            val killed = mutableSetOf<MutantId>()
            val seen = mutableSetOf<Int>()
            val limit = minOf(suiteSize, pool.size)
            while (seen.size < limit) {
              val idx = rng.nextInt(pool.size)
              if (seen.add(idx)) {
                val entry = pool[idx]
                if (entry.nextTickG0Failed == true) killed.add(entry.mutantId)
              }
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Builds a test suite of up to [suiteSize] ticks by cycling round-robin through [groups], drawing
   * one tick **without replacement** from the current group at each slot. Each group maintains its
   * own `Set` of used indices so no tick is drawn twice within a repetition.
   *
   * When a group is exhausted it is skipped; the loop stops early once every group is exhausted
   * (detected by [groups].size consecutive exhausted-group visits) or [suiteSize] is reached.
   * Repetitions run in parallel with deterministic per-rep seeds.
   *
   * @param groups Pre-partitioned tick lists (e.g., by TSC instance or leaf node).
   * @param suiteSize Maximum number of ticks to include in each test suite.
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
            val usedPerGroup = Array(groups.size) { mutableSetOf<Int>() }
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            var slot = 0
            var consecutiveExhausted = 0
            while (drawn < suiteSize && consecutiveExhausted < groups.size) {
              val g = slot % groups.size
              slot++
              val group = groups[g]
              val used = usedPerGroup[g]
              if (used.size >= group.size) {
                consecutiveExhausted++
                continue
              }
              consecutiveExhausted = 0
              var idx: Int
              do {
                idx = rng.nextInt(group.size)
              } while (!used.add(idx))
              val entry = group[idx]
              if (entry.nextTickG0Failed == true) killed.add(entry.mutantId)
              drawn++
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Variant of [evaluate] where the kill count per repetition is determined by looking up, for each
   * drawn tick's scenario configuration, how many mutants were killed in that scenario according to
   * [MutantScenarioG0ViolationsView]. A tick's contribution is the full set of mutants killed in
   * its scenario rather than just the tick's own `nextTickG0Failed` flag.
   *
   * The view is loaded once; tick data and groupings are shared with the same single DB load as
   * [evaluate].
   */
  fun evaluateWithStartingScenario() {
    println("Starting BaselineNextTickPostEvaluation (scenario mode).")

    val fullRunId = db { DecisionTreeRunsRepository.getLatestFullRunId() }
    if (fullRunId != null) {
      println("  Using leaf assignments from full run ${fullRunId.value}.")
    } else {
      println("  No full run (train_fraction=1.0) found — leaf strategy will be skipped.")
    }

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations(forRunId = fullRunId) }
    println("  Loaded ${allTicks.size} ticks.")

    println("  Loading scenario kill map from view...")
    val allViolations = MutantScenarioG0ViolationsView.getAll()
    val scenarioKills = buildScenarioKillMap(allViolations)
    val rareMutantIds = buildRareMutantIds(allViolations)
    println("  Loaded kill data for ${scenarioKills.size} scenario configs.")
    println("  Found ${rareMutantIds.size} rare mutants (<= $MAX_RARE_MUTANT_FAILURES violations).")

    println("  Grouping ticks by TSC instance...")
    val tscGroups = allTicks.groupBy { it.tscInstanceId }.values.toList()
    println("  Loaded ${tscGroups.size} TSC groups.")

    val leafGroups: List<List<NextTickPostEvaluationDatabaseEntry>> =
        if (fullRunId != null) {
          println("  Grouping ticks by leaf node...")
          allTicks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }.values.toList()
        } else {
          emptyList()
        }
    println("  Loaded ${leafGroups.size} decision-tree leaf groups.")

    val leafGroupsWithAccidents =
        leafGroups.filter { group -> group.any { it.nextTickG0Failed == true } }
    if (leafGroups.isNotEmpty()) {
      println(
          "  ${leafGroupsWithAccidents.size} of ${leafGroups.size} leaf groups contain at least one accident tick.")
    }

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (scenario mode):")

      println("    Evaluating uniform-random scenario sampling.")
      save(
          evaluateRandomDrawScenario(allTicks, scenarioKills, suiteSize),
          "random_scenario",
          suiteSize)

      println("    Evaluating TSC-instance-stratified scenario sampling.")
      save(
          evaluateRoundRobinScenario(tscGroups, scenarioKills, suiteSize),
          "tsc_scenario",
          suiteSize)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating decision-tree-leaf-stratified scenario sampling.")
        save(
            evaluateRoundRobinScenario(leafGroups, scenarioKills, suiteSize),
            "leaf_scenario",
            suiteSize)
      }

      if (leafGroupsWithAccidents.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups only).")
        save(
            evaluateRoundRobinScenario(leafGroupsWithAccidents, scenarioKills, suiteSize),
            "leaf_scenario_accidents",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random scenario sampling (rare mutants).")
        save(
            evaluateRandomDrawScenario(allTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare",
            suiteSize)

        println("    Evaluating TSC-instance-stratified scenario sampling (rare mutants).")
        save(
            evaluateRoundRobinScenario(tscGroups, scenarioKills, suiteSize, rareMutantIds),
            "tsc_scenario_rare",
            suiteSize)

        if (leafGroups.isNotEmpty()) {
          println("    Evaluating decision-tree-leaf-stratified scenario sampling (rare mutants).")
          save(
              evaluateRoundRobinScenario(leafGroups, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_rare",
              suiteSize)
        }

        if (leafGroupsWithAccidents.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups, rare mutants).")
          save(
              evaluateRoundRobinScenario(
                  leafGroupsWithAccidents, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_accidents_rare",
              suiteSize)
        }
      }
    }

    println("Finished BaselineNextTickPostEvaluation (scenario mode).")
  }

  /**
   * Variant of [evaluateSplit] where kill counting uses [MutantScenarioG0ViolationsView] restricted
   * to the test-set mutants: for each drawn tick, all test-set mutants killed in its scenario
   * contribute to the result.
   */
  fun evaluateSplitWithStartingScenario() {
    println("Starting BaselineNextTickPostEvaluation (scenario split mode).")

    val testMutantIds = db { DecisionTreeRunsRepository.getLatestRunTestMutantIds() }
    if (testMutantIds.isNullOrEmpty()) {
      println(
          "  No decision tree run with a test set of mutants found in database. Skipping scenario split evaluation.")
      return
    }
    println("  Loaded ${testMutantIds.size} test-set mutant IDs from latest run.")
    val testMutantIdSet = testMutantIds.toHashSet()

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations() }
    println("  Loaded ${allTicks.size} ticks.")

    val filteredTicks = allTicks.filter { it.mutantId in testMutantIdSet }
    println("  Filtered to ${filteredTicks.size} ticks for test-set mutants.")

    println("  Loading scenario kill map (test mutants only) from view...")
    val allViolations = MutantScenarioG0ViolationsView.getAll()
    val scenarioKills = buildScenarioKillMap(allViolations, testMutantIdSet)
    val rareMutantIds = buildRareMutantIds(allViolations).intersect(testMutantIdSet)
    println("  Loaded kill data for ${scenarioKills.size} scenario configs.")
    println(
        "  Found ${rareMutantIds.size} rare mutants among test set (<= $MAX_RARE_MUTANT_FAILURES violations).")

    println("  Grouping ticks by TSC instance...")
    val filteredTscGroups = filteredTicks.groupBy { it.tscInstanceId }.values.toList()
    println("  Grouping ticks by leaf node...")
    val filteredLeafGroups =
        filteredTicks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }.values.toList()

    val filteredLeafGroupsWithAccidents =
        filteredLeafGroups.filter { group -> group.any { it.nextTickG0Failed == true } }
    println(
        "  ${filteredLeafGroupsWithAccidents.size} of ${filteredLeafGroups.size} leaf groups contain at least one accident tick.")

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (scenario split mode):")

      println("    Evaluating uniform-random scenario sampling (split).")
      save(
          evaluateRandomDrawScenario(filteredTicks, scenarioKills, suiteSize),
          "random_scenario_split",
          suiteSize)

      println("    Evaluating TSC-instance-stratified scenario sampling (split).")
      save(
          evaluateRoundRobinScenario(filteredTscGroups, scenarioKills, suiteSize),
          "tsc_scenario_split",
          suiteSize)

      println("    Evaluating decision-tree-leaf-stratified scenario sampling (split).")
      save(
          evaluateRoundRobinScenario(filteredLeafGroups, scenarioKills, suiteSize),
          "leaf_scenario_split",
          suiteSize)

      if (filteredLeafGroupsWithAccidents.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups only, split).")
        save(
            evaluateRoundRobinScenario(filteredLeafGroupsWithAccidents, scenarioKills, suiteSize),
            "leaf_scenario_accidents_split",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random scenario sampling (rare mutants, split).")
        save(
            evaluateRandomDrawScenario(filteredTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare_split",
            suiteSize)

        println("    Evaluating TSC-instance-stratified scenario sampling (rare mutants, split).")
        save(
            evaluateRoundRobinScenario(filteredTscGroups, scenarioKills, suiteSize, rareMutantIds),
            "tsc_scenario_rare_split",
            suiteSize)

        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (rare mutants, split).")
        save(
            evaluateRoundRobinScenario(filteredLeafGroups, scenarioKills, suiteSize, rareMutantIds),
            "leaf_scenario_rare_split",
            suiteSize)

        if (filteredLeafGroupsWithAccidents.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups, rare mutants, split).")
          save(
              evaluateRoundRobinScenario(
                  filteredLeafGroupsWithAccidents, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_accidents_rare_split",
              suiteSize)
        }
      }
    }

    println("Finished BaselineNextTickPostEvaluation (scenario split mode).")
  }

  /**
   * Builds a lookup map from scenario config ID to the set of mutant IDs killed in that scenario.
   * Only entries where [MutantScenarioG0Violation.anyG0Violation] is `true` are included.
   *
   * @param allViolations Pre-loaded rows from [MutantScenarioG0ViolationsView].
   * @param mutantFilter If non-null, restricts kill entries to the given mutant IDs.
   */
  private fun buildScenarioKillMap(
      allViolations: List<MutantScenarioG0Violation>,
      mutantFilter: Set<Int>? = null,
  ): Map<Int, Set<Int>> =
      allViolations
          .filter { it.anyG0Violation && (mutantFilter == null || it.mutantId in mutantFilter) }
          .groupBy { it.scenarioConfigId }
          .mapValues { (_, vs) -> vs.map { it.mutantId }.toSet() }

  /**
   * Returns the set of mutant IDs that are considered "rare": mutants for which the number of
   * scenario configs with [MutantScenarioG0Violation.anyG0Violation] = `true` is at most
   * [MAX_RARE_MUTANT_FAILURES].
   *
   * @param allViolations Pre-loaded rows from [MutantScenarioG0ViolationsView].
   */
  private fun buildRareMutantIds(allViolations: List<MutantScenarioG0Violation>): Set<Int> =
      allViolations
          .groupBy { it.mutantId }
          .filterValues { rows -> rows.count { it.anyG0Violation } in 1..MAX_RARE_MUTANT_FAILURES }
          .keys

  /**
   * Draws up to [suiteSize] ticks uniformly at random **without replacement** from [pool],
   * [REPETITIONS] times. For each drawn tick the full set of mutants killed in its scenario (from
   * [scenarioKills]) is added to the result. If the pool is smaller than [suiteSize] all items are
   * drawn and the loop stops early.
   *
   * When [rareMutantIds] is non-null only kills of those mutants are counted.
   *
   * Repetitions run in parallel with deterministic per-rep seeds.
   */
  private fun evaluateRandomDrawScenario(
      pool: List<NextTickPostEvaluationDatabaseEntry>,
      scenarioKills: Map<Int, Set<Int>>,
      suiteSize: Int,
      rareMutantIds: Set<Int>? = null,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val killed = mutableSetOf<Int>()
            val seen = mutableSetOf<Int>()
            val limit = minOf(suiteSize, pool.size)
            while (seen.size < limit) {
              val idx = rng.nextInt(pool.size)
              if (seen.add(idx)) {
                val entry = pool[idx]
                scenarioKills[entry.scenarioConfigId]?.let { kills ->
                  killed.addAll(
                      if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
                }
              }
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Round-robin variant of [evaluateRandomDrawScenario]: cycles through [groups], drawing one
   * **unique scenario** (by [NextTickPostEvaluationDatabaseEntry.scenarioConfigId]) from the
   * current group at each slot **without replacement**, then credits the full set of mutants killed
   * in that scenario via [scenarioKills].
   *
   * Each repetition starts at a random group. Exhausted groups are removed from the active rotation
   * immediately so they are never visited again. The loop stops once [suiteSize] scenarios have
   * been drawn or all groups are exhausted. Repetitions run in parallel with deterministic per-rep
   * seeds.
   *
   * The draw within each group is weighted by tick count (scenarios with more ticks in the group
   * are proportionally more likely to be drawn first). A rejected-candidate retry loop handles
   * collisions; it is bounded because the exhaustion guard ensures at least one unused scenario
   * remains before entering.
   *
   * When [rareMutantIds] is non-null only kills of those mutants are counted.
   */
  private fun evaluateRoundRobinScenario(
      groups: List<List<NextTickPostEvaluationDatabaseEntry>>,
      scenarioKills: Map<Int, Set<Int>>,
      suiteSize: Int,
      rareMutantIds: Set<Int>? = null,
  ): List<Int> {
    val scenarioIdListsPerGroup = groups.map { group -> group.map { it.scenarioConfigId } }
    val uniqueScenarioCountPerGroup = scenarioIdListsPerGroup.map { it.distinct().size }

    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          println("  Start with repetition: $rep")
          val rng = Random(42L + rep)
          val alreadyUsedPerGroup = Array(groups.size) { mutableSetOf<StartingScenarioId>() }
          val activeGroups = (0 until groups.size).toMutableList()
          val killed = mutableSetOf<MutantId>()
          var drawn = 0
          var pos = rng.nextInt(activeGroups.size)

          while (drawn < suiteSize && activeGroups.isNotEmpty()) {
            val groupIdx = activeGroups[pos]
            println("  Draw from Group $groupIdx")
            val alreadyUsed = alreadyUsedPerGroup[groupIdx]

            if (alreadyUsed.size >= uniqueScenarioCountPerGroup[groupIdx]) {
              println("  Group $groupIdx was exhausted")
              activeGroups.removeAt(pos)
              if (activeGroups.isEmpty()) break
              pos %= activeGroups.size
              continue
            }

            val scenarioIds = scenarioIdListsPerGroup[groupIdx]
            var scenarioId: StartingScenarioId
            do {
              scenarioId = scenarioIds[rng.nextInt(scenarioIds.size)]
              println("    Draw Scenario $scenarioId")
            } while (!alreadyUsed.add(scenarioId))

            scenarioKills[scenarioId]?.let { kills ->
              killed.addAll(
                  if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
            }
            drawn++
            pos = (pos + 1) % activeGroups.size
          }
          killed.size
        }
        .collect(Collectors.toList())
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
