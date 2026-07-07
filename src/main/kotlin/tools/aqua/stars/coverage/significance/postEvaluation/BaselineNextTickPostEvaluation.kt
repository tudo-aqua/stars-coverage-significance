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
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioG0Violation
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioG0ViolationsView
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.SamplingData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.StartingScenarioId
import tools.aqua.stars.coverage.significance.scenarioIds

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
   * Groups [ticks] by TSC instance and (when [hasLeaf] is `true`) by DC leaf node, and pre-extracts
   * scenario config ID lists for each grouping. Called once per public evaluation function so all
   * strategy variants share the same in-memory structures.
   *
   * @param ticks The tick list to group (may be the full dataset or a filtered subset).
   * @param hasLeaf Whether leaf node IDs are populated in [ticks]; when `false` all leaf-related
   *   fields in the returned [SamplingData] are empty lists.
   */
  private fun buildSamplingData(
      ticks: List<NextTickPostEvaluationDatabaseEntry>,
      hasLeaf: Boolean,
  ): SamplingData {
    println("  Grouping ticks by TSC instance...")
    val tscGroups = ticks.groupBy { it.tscInstanceId }.values.toList()
    println("  ${tscGroups.size} TSC groups.")

    val dcLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>
    val accidentDCLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>
    val startingScenarioIdsPerDCLeafId: List<Set<StartingScenarioId>>
    val countOfUniqueStartingScenariosPerDCLeafId: List<Int>
    val startingScenarioIdsPerAccidentDCLeafId: List<Set<StartingScenarioId>>
    val countOfUniqueStartingScenariosPerAccidentDCLeafId: List<Int>

    if (hasLeaf) {
      println("  Grouping ticks by DC leaf node...")
      val allTicksGroupedByDCLeafId =
          ticks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }

      // Grouping of all ticks
      dcLeafGroups = allTicksGroupedByDCLeafId.values.toList()
      startingScenarioIdsPerDCLeafId =
          dcLeafGroups.map { databaseEntriesForDCLeafId ->
            databaseEntriesForDCLeafId.map { it.scenarioConfigId }.toSet()
          }
      countOfUniqueStartingScenariosPerDCLeafId =
          startingScenarioIdsPerDCLeafId.map { it.distinct().size }

      // Grouping of all ticks that lead to an accident
      accidentDCLeafGroups =
          dcLeafGroups.filter { group -> group.any { it.nextTickG0Failed == true } }
      println(
          "  ${accidentDCLeafGroups.size} of ${dcLeafGroups.size} DC leaf groups contain accidents.")
      startingScenarioIdsPerAccidentDCLeafId =
          accidentDCLeafGroups.map { databaseEntriesForAccidentDCLeafId ->
            databaseEntriesForAccidentDCLeafId.map { it.scenarioConfigId }.toSet()
          }
      countOfUniqueStartingScenariosPerAccidentDCLeafId =
          startingScenarioIdsPerAccidentDCLeafId.map { it.distinct().size }
    } else {
      dcLeafGroups = emptyList()
      accidentDCLeafGroups = emptyList()
      startingScenarioIdsPerDCLeafId = emptyList()
      countOfUniqueStartingScenariosPerDCLeafId = emptyList()
      startingScenarioIdsPerAccidentDCLeafId = emptyList()
      countOfUniqueStartingScenariosPerAccidentDCLeafId = emptyList()
    }

    val allScenarioIds = ScenarioStartingConfigurationRepository.getAll().map { it.id!! }.toSet()
    val tscScenarioIdLists =
        tscGroups.map { tscGroup -> tscGroup.map { it.scenarioConfigId }.toSet() }
    val tscUniquePerGroup = tscScenarioIdLists.map { it.distinct().size }

    return SamplingData(
        allTicks = ticks,
        tscGroups = tscGroups,
        dcLeafGroups = dcLeafGroups,
        accidentDCLeafGroups = accidentDCLeafGroups,
        allScenarioIds = allScenarioIds,
        tscScenarioIdLists = tscScenarioIdLists,
        tscUniquePerGroup = tscUniquePerGroup,
        startingScenarioIdsPerDCLeafId = startingScenarioIdsPerDCLeafId,
        countOfUniqueStartingScenariosPerDCLeafId = countOfUniqueStartingScenariosPerDCLeafId,
        startingScenarioIdsPerAccidentDCLeafId = startingScenarioIdsPerAccidentDCLeafId,
        countOfUniqueStartingScenariosPerAccidentDCLeafId =
            countOfUniqueStartingScenariosPerAccidentDCLeafId,
    )
  }

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

    val data = buildSamplingData(allTicks, hasLeaf = fullRunId != null)

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize:")

      println("    Evaluating uniform-random tick sampling.")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(evaluateRandomDraw(data.allTicks, suiteSize), "random", suiteSize)

      println("    Evaluating TSC-instance-stratified tick sampling.")
      data.tscGroups.forEachIndexed { index, ticks ->
        println("      TSC Group '$index': ${ticks.size} entries.")
      }
      println("      Suite Size: $suiteSize.")
      save(evaluateRoundRobin(data.tscGroups, suiteSize), "tsc", suiteSize)

      if (data.dcLeafGroups.isNotEmpty()) {
        println("    Evaluating decision-tree-leaf-stratified tick sampling.")
        data.dcLeafGroups.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        println("      Suite Size: $suiteSize.")
        save(evaluateRoundRobin(data.dcLeafGroups, suiteSize), "leaf", suiteSize)
      }
    }

    println("Finished BaselineNextTickPostEvaluation.")
  }

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

    val data = buildSamplingData(allTicks, hasLeaf = fullRunId != null)

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (scenario mode):")

      println("    Evaluating uniform-random scenario sampling.")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRandomDrawScenario(data.allScenarioIds, scenarioKills, suiteSize),
          "random_scenario",
          suiteSize)

      println("    Evaluating TSC-instance-stratified scenario sampling.")
      data.tscGroups.forEachIndexed { index, ticks ->
        println("      TSC Group '$index': ${ticks.size} entries.")
      }
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRoundRobinScenario(data.tscGroups, scenarioKills, suiteSize),
          "tsc_scenario",
          suiteSize)

      if (data.dcLeafGroups.isNotEmpty()) {
        println("    Evaluating decision-tree-leaf-stratified scenario sampling.")
        data.dcLeafGroups.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.dcLeafGroups, scenarioKills, suiteSize),
            "leaf_scenario",
            suiteSize)
      }

      if (data.accidentDCLeafGroups.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups only).")
        data.accidentDCLeafGroups.forEachIndexed { index, ticks ->
          println("      Accident DC Leaf Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.accidentDCLeafGroups, scenarioKills, suiteSize),
            "leaf_scenario_accidents",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random scenario sampling (rare mutants).")
        println("      Tick Pool: ${data.allTicks.size} entries.")
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRandomDrawScenario(
                data.allScenarioIds, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare",
            suiteSize)

        println("    Evaluating TSC-instance-stratified scenario sampling (rare mutants).")
        data.tscGroups.forEachIndexed { index, ticks ->
          println("      TSC Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.tscGroups, scenarioKills, suiteSize, rareMutantIds),
            "tsc_scenario_rare",
            suiteSize)

        if (data.dcLeafGroups.isNotEmpty()) {
          println("    Evaluating decision-tree-leaf-stratified scenario sampling (rare mutants).")
          data.dcLeafGroups.forEachIndexed { index, ticks ->
            println("      DC Leaf Group '$index': ${ticks.size} entries.")
          }
          println("      Scenario Kill Map: ${scenarioKills.size} entries.")
          println("      Rare Mutants: ${rareMutantIds.size} entries.")
          println("      Suite Size: $suiteSize.")
          save(
              evaluateRoundRobinScenario(
                  data.dcLeafGroups, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_rare",
              suiteSize)
        }

        if (data.accidentDCLeafGroups.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups, rare mutants).")
          data.accidentDCLeafGroups.forEachIndexed { index, ticks ->
            println("      Accident DC Leaf Group '$index': ${ticks.size} entries.")
          }
          println("      Scenario Kill Map: ${scenarioKills.size} entries.")
          println("      Rare Mutants: ${rareMutantIds.size} entries.")
          println("      Suite Size: $suiteSize.")
          save(
              evaluateRoundRobinScenario(
                  data.accidentDCLeafGroups, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_accidents_rare",
              suiteSize)
        }
      }
    }

    println("Finished BaselineNextTickPostEvaluation (scenario mode).")
  }

  /**
   * For each mutant that causes at least one G0 accident, measures how many starting scenarios each
   * sampling strategy requires (without replacement) before the mutant is first killed.
   *
   * "Killed" means a drawn [StartingScenarioId] belongs to the mutant's set of killing scenarios
   * according to [MutantScenarioG0ViolationsView]. Each of the [REPETITIONS] repetitions produces
   * one draw count; -1 is recorded if the entire pool was exhausted without a kill (should not
   * occur for accident mutants). Results are written to
   * `baseline_next_tick/time_to_kill/mutant_<id>/ttk_<strategy>.csv`.
   *
   * Strategies evaluated:
   * 1. **Random** — uniform draw from the tick-weighted scenario pool.
   * 2. **TSC** — round-robin across TSC-instance groups.
   * 3. **Leaf** — round-robin across DC leaf groups (requires a full run).
   * 4. **Leaf (accidents)** — round-robin restricted to DC leaf groups containing accident ticks.
   */
  fun evaluateTimeToKill() {
    println("Starting BaselineNextTickPostEvaluation (time to kill).")

    val fullRunId = db { DecisionTreeRunsRepository.getLatestFullRunId() }
    if (fullRunId != null) {
      println("  Using leaf assignments from full run ${fullRunId.value}.")
    } else {
      println("  No full run (train_fraction=1.0) found — leaf strategies will be skipped.")
    }

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations(forRunId = fullRunId) }
    println("  Loaded ${allTicks.size} ticks.")

    println("  Loading scenario kill data from view...")
    val allViolations = MutantScenarioG0ViolationsView.getAll()
    val killingScenariosByMutant =
        allViolations
            .filter { it.anyG0Violation }
            .groupBy { it.mutantId }
            .mapValues { (_, values) -> values.map { it.scenarioConfigId }.toSet() }
    val accidentMutantIds = killingScenariosByMutant.keys.sorted()
    println("  Found ${accidentMutantIds.size} mutants that cause accidents.")

    val data = buildSamplingData(allTicks, hasLeaf = fullRunId != null)

    for (mutantId in accidentMutantIds) {
      val killingScenarios = killingScenariosByMutant.getValue(mutantId)
      println("  Mutant $mutantId (${killingScenarios.size} killing scenarios):")

      println("    Evaluating random time-to-kill.")
      println("      Scenario ID Pool: ${data.allScenarioIds.size} entries.")
      println("      Killing Scenarios: ${killingScenarios.size} entries.")
      saveTimeToKill(
          evaluateTimeToKillRandom(data.allScenarioIds, killingScenarios), "random", mutantId)

      println("    Evaluating TSC-stratified time-to-kill.")
      data.tscScenarioIdLists.forEachIndexed { index, ints ->
        println("      TSC Group '$index': ${ints.size} entries.")
      }
      println("      Killing Scenarios: ${killingScenarios.size} entries.")
      saveTimeToKill(
          evaluateTimeToKillRoundRobin(data.tscScenarioIdLists, killingScenarios), "tsc", mutantId)

      if (data.startingScenarioIdsPerDCLeafId.isNotEmpty()) {
        println("    Evaluating leaf-stratified time-to-kill.")
        data.startingScenarioIdsPerDCLeafId.forEachIndexed { index, scenarioIds ->
          println("      DC Leaf Group '$index': ${scenarioIds.size} entries.")
        }
        println("      Killing Scenarios: ${killingScenarios.size} entries.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(data.startingScenarioIdsPerDCLeafId, killingScenarios),
            "leaf",
            mutantId)
      }

      if (data.startingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
        println("    Evaluating accident-leaf-stratified time-to-kill.")
        data.startingScenarioIdsPerAccidentDCLeafId.forEachIndexed { index, scenarioIds ->
          println("      Accident DC Leaf Group '$index': ${scenarioIds.size} entries.")
        }
        println("      Killing Scenarios: ${killingScenarios.size} entries.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(
                data.startingScenarioIdsPerAccidentDCLeafId, killingScenarios),
            "leaf_accidents",
            mutantId)
      }
    }

    println("Finished BaselineNextTickPostEvaluation (time to kill).")
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
            val order = (0 until groups.size).shuffled(rng)
            val usedPerGroup = Array(groups.size) { mutableSetOf<Int>() }
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            var slot = 0
            var consecutiveExhausted = 0
            while (drawn < suiteSize && consecutiveExhausted < groups.size) {
              val g = order[slot % groups.size]
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

  private fun evaluateRandomDrawScenario(
      pool: Set<StartingScenarioId>,
      scenarioKills: Map<Int, Set<Int>>,
      suiteSize: Int,
      rareMutantIds: Set<Int>? = null,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val scenarioIds = pool.shuffled(rng).toMutableList()
            val killed = mutableSetOf<MutantId>()
            val drawnScenarios = mutableSetOf<StartingScenarioId>()
            while (drawnScenarios.size < suiteSize && scenarioIds.isNotEmpty()) {
              val scenarioId = scenarioIds.removeFirst()
              drawnScenarios.add(scenarioId)
              scenarioKills[scenarioId]?.let { kills ->
                killed.addAll(
                    if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
              }
            }
            killed.size
          }
          .collect(Collectors.toList())

  private fun evaluateRoundRobinScenario(
      groups: List<List<NextTickPostEvaluationDatabaseEntry>>,
      scenarioKills: Map<Int, Set<Int>>,
      suiteSize: Int,
      rareMutantIds: Set<Int>? = null,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val scenarioIdSetsPerGroup =
                groups
                    .map { group ->
                      group.map { it.scenarioConfigId }.toSet().shuffled(rng).toMutableList()
                    }
                    .shuffled(rng)
                    .toMutableList()
            val killed = mutableSetOf<MutantId>()
            val drawnScenarios = mutableListOf<StartingScenarioId>()
            var pos = 0
            while (scenarioIdSetsPerGroup.isNotEmpty() && drawnScenarios.size < suiteSize) {
              val currentGroup = scenarioIdSetsPerGroup[pos]
              val scenarioId = currentGroup.removeFirst()
              // Track current scenario
              drawnScenarios.add(scenarioId)
              // Remove drawn scenario from all groups
              scenarioIdSetsPerGroup.forEach { it.remove(scenarioId) }
              // Compute position correction before compacting the list
              val currentGroupExhausted = currentGroup.isEmpty()
              val emptyBeforePos = (0 until pos).count { scenarioIdSetsPerGroup[it].isEmpty() }
              // Remove all empty groups
              scenarioIdSetsPerGroup.removeAll { it.isEmpty() }
              scenarioKills[scenarioId]?.let { kills ->
                killed.addAll(
                    if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
              }
              if (scenarioIdSetsPerGroup.isEmpty()) break
              val effectivePos = pos - emptyBeforePos
              pos =
                  if (currentGroupExhausted) effectivePos % scenarioIdSetsPerGroup.size
                  else (effectivePos + 1) % scenarioIdSetsPerGroup.size
            }
            killed.size
          }
          .collect(Collectors.toList())

  private fun evaluateTimeToKillRandom(
      scenarioIdPool: Set<StartingScenarioId>,
      killingScenarios: Set<StartingScenarioId>,
  ): List<Int> {
    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          val rng = Random(42L + rep)
          val seen = mutableSetOf<StartingScenarioId>()
          val pool = scenarioIdPool.shuffled(rng).toMutableList()
          while (pool.isNotEmpty()) {
            val scenarioId = pool.removeFirst()
            seen.add(scenarioId)
            if (scenarioId in killingScenarios) {
              return@map seen.size
            }
          }
          -1
        }
        .collect(Collectors.toList())
  }

  private fun evaluateTimeToKillRoundRobin(
      scenarioIdListsPerGroup: List<Set<StartingScenarioId>>,
      killingScenarios: Set<StartingScenarioId>,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val activeGroups =
                scenarioIdListsPerGroup
                    .map { it.shuffled(rng).toMutableList() }
                    .shuffled(rng)
                    .toMutableList()
            var pos = 0
            var killedAt = 0

            while (activeGroups.isNotEmpty()) {
              killedAt++
              val currentGroup = activeGroups[pos]
              val currentScenario = currentGroup.removeFirst()

              if (currentScenario in killingScenarios) {
                return@map killedAt
              }

              activeGroups.forEach { it.remove(currentScenario) }
              // Compute position correction before compacting the list
              val currentGroupExhausted = currentGroup.isEmpty()
              val emptyBeforePos = (0 until pos).count { activeGroups[it].isEmpty() }
              activeGroups.removeAll { it.isEmpty() }
              if (activeGroups.isEmpty()) break
              val effectivePos = pos - emptyBeforePos
              pos =
                  if (currentGroupExhausted) effectivePos % activeGroups.size
                  else (effectivePos + 1) % activeGroups.size
            }
            -1
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

  private fun saveTimeToKill(results: List<Int>, strategy: String, mutantId: Int) {
    val path = BASE_PATH.resolve("time_to_kill/mutant_${mutantId}/ttk_${strategy}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(
            prefix = "draws_to_kill\n",
            separator = "\n",
        ) {
          it.toString()
        })
    println("    CSV written to: $path")
  }
}
