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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.SamplingData
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
    val startingScenarioIdsPerDCLeafId: List<List<StartingScenarioId>>
    val countOfUniqueStartingScenariosPerDCLeafId: List<Int>
    val startingScenarioIdsPerAccidentDCLeafId: List<List<StartingScenarioId>>
    val countOfUniqueStartingScenariosPerAccidentDCLeafId: List<Int>

    if (hasLeaf) {
      println("  Grouping ticks by DC leaf node...")
      val allTicksGroupedByDCLeafId =
          ticks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId }

      // Grouping of all ticks
      dcLeafGroups = allTicksGroupedByDCLeafId.values.toList()
      startingScenarioIdsPerDCLeafId =
          dcLeafGroups.map { databaseEntriesForDCLeafId ->
            databaseEntriesForDCLeafId.map { it.scenarioConfigId }
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
            databaseEntriesForAccidentDCLeafId.map { it.scenarioConfigId }
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

    val allScenarioIds = ticks.map { it.scenarioConfigId }
    val tscScenarioIdLists = tscGroups.map { tscGroup -> tscGroup.map { it.scenarioConfigId } }
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

    val data = buildSamplingData(filteredTicks, hasLeaf = true)

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (split):")

      println("    Evaluating uniform-random tick sampling (split).")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(evaluateRandomDraw(data.allTicks, suiteSize), "random_split", suiteSize)

      println("    Evaluating TSC-instance-stratified tick sampling (split).")
      data.tscGroups.forEachIndexed { index, ticks ->
        println("      TSC Group '$index': ${ticks.size} entries.")
      }
      println("      Suite Size: $suiteSize.")
      save(evaluateRoundRobin(data.tscGroups, suiteSize), "tsc_split", suiteSize)

      println("    Evaluating decision-tree-leaf-stratified tick sampling (split).")
      data.dcLeafGroups.forEachIndexed { index, ticks ->
        println("      DC Leaf Group '$index': ${ticks.size} entries.")
      }
      println("      Suite Size: $suiteSize.")
      save(evaluateRoundRobin(data.dcLeafGroups, suiteSize), "leaf_split", suiteSize)
    }

    println("Finished BaselineNextTickPostEvaluation (split mode).")
  }

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

    val data = buildSamplingData(allTicks, hasLeaf = fullRunId != null)

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (scenario mode):")

      println("    Evaluating uniform-random scenario sampling.")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRandomDrawScenario(data.allTicks, scenarioKills, suiteSize),
          "random_scenario",
          suiteSize)

      println("    Evaluating uniform-random scenario sampling (with replacement).")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRandomDrawScenarioWithReplacement(data.allTicks, scenarioKills, suiteSize),
          "random_scenario_replacement",
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
            evaluateRandomDrawScenario(data.allTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare",
            suiteSize)

        println("    Evaluating uniform-random scenario sampling (with replacement, rare mutants).")
        println("      Tick Pool: ${data.allTicks.size} entries.")
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRandomDrawScenarioWithReplacement(
                data.allTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_replacement_rare",
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

    val data = buildSamplingData(filteredTicks, hasLeaf = true)

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize (scenario split mode):")

      println("    Evaluating uniform-random scenario sampling (split).")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRandomDrawScenario(data.allTicks, scenarioKills, suiteSize),
          "random_scenario_split",
          suiteSize)

      println("    Evaluating uniform-random scenario sampling (with replacement, split).")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRandomDrawScenarioWithReplacement(data.allTicks, scenarioKills, suiteSize),
          "random_scenario_replacement_split",
          suiteSize)

      println("    Evaluating TSC-instance-stratified scenario sampling (split).")
      data.tscGroups.forEachIndexed { index, ticks ->
        println("      TSC Group '$index': ${ticks.size} entries.")
      }
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRoundRobinScenario(data.tscGroups, scenarioKills, suiteSize),
          "tsc_scenario_split",
          suiteSize)

      println("    Evaluating decision-tree-leaf-stratified scenario sampling (split).")
      data.dcLeafGroups.forEachIndexed { index, ticks ->
        println("      DC Leaf Group '$index': ${ticks.size} entries.")
      }
      println("      Scenario Kill Map: ${scenarioKills.size} entries.")
      println("      Suite Size: $suiteSize.")
      save(
          evaluateRoundRobinScenario(data.dcLeafGroups, scenarioKills, suiteSize),
          "leaf_scenario_split",
          suiteSize)

      if (data.accidentDCLeafGroups.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups only, split).")
        data.accidentDCLeafGroups.forEachIndexed { index, ticks ->
          println("      Accident DC Leaf Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.accidentDCLeafGroups, scenarioKills, suiteSize),
            "leaf_scenario_accidents_split",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random scenario sampling (rare mutants, split).")
        println("      Tick Pool: ${data.allTicks.size} entries.")
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRandomDrawScenario(data.allTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare_split",
            suiteSize)

        println(
            "    Evaluating uniform-random scenario sampling (with replacement, rare mutants, split).")
        println("      Tick Pool: ${data.allTicks.size} entries.")
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRandomDrawScenarioWithReplacement(
                data.allTicks, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_replacement_rare_split",
            suiteSize)

        println("    Evaluating TSC-instance-stratified scenario sampling (rare mutants, split).")
        data.tscGroups.forEachIndexed { index, ticks ->
          println("      TSC Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.tscGroups, scenarioKills, suiteSize, rareMutantIds),
            "tsc_scenario_rare_split",
            suiteSize)

        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (rare mutants, split).")
        data.dcLeafGroups.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        println("      Scenario Kill Map: ${scenarioKills.size} entries.")
        println("      Rare Mutants: ${rareMutantIds.size} entries.")
        println("      Suite Size: $suiteSize.")
        save(
            evaluateRoundRobinScenario(data.dcLeafGroups, scenarioKills, suiteSize, rareMutantIds),
            "leaf_scenario_rare_split",
            suiteSize)

        if (data.accidentDCLeafGroups.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups, rare mutants, split).")
          data.accidentDCLeafGroups.forEachIndexed { index, ticks ->
            println("      Accident DC Leaf Group '$index': ${ticks.size} entries.")
          }
          println("      Scenario Kill Map: ${scenarioKills.size} entries.")
          println("      Rare Mutants: ${rareMutantIds.size} entries.")
          println("      Suite Size: $suiteSize.")
          save(
              evaluateRoundRobinScenario(
                  data.accidentDCLeafGroups, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_accidents_rare_split",
              suiteSize)
        }
      }
    }

    println("Finished BaselineNextTickPostEvaluation (scenario split mode).")
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
          evaluateTimeToKillRoundRobin(
              data.tscScenarioIdLists, data.tscUniquePerGroup, killingScenarios),
          "tsc",
          mutantId)

      if (data.startingScenarioIdsPerDCLeafId.isNotEmpty()) {
        println("    Evaluating leaf-stratified time-to-kill.")
        data.startingScenarioIdsPerDCLeafId.forEachIndexed { index, scenarioIds ->
          println("      DC Leaf Group '$index': ${scenarioIds.size} entries.")
        }
        println("      Killing Scenarios: ${killingScenarios.size} entries.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(
                data.startingScenarioIdsPerDCLeafId,
                data.countOfUniqueStartingScenariosPerDCLeafId,
                killingScenarios),
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
                data.startingScenarioIdsPerAccidentDCLeafId,
                data.countOfUniqueStartingScenariosPerAccidentDCLeafId,
                killingScenarios),
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

  /**
   * Draws up to [suiteSize] unique scenarios without replacement from [pool], [REPETITIONS] times.
   * For each drawn scenario the full set of mutants killed in it (from [scenarioKills]) is added to
   * the result. The draw is weighted by tick count: a scenario that appears N times in [pool] is N
   * times more likely to be the next draw. If the number of unique scenarios in [pool] is smaller
   * than [suiteSize] all scenarios are drawn and the loop stops early.
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
  ): List<Int> {
    val scenarioIds = pool.map { it.scenarioConfigId }
    val uniqueScenarioCount = scenarioIds.distinct().size

    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          val rng = Random(42L + rep)
          val killed = mutableSetOf<MutantId>()
          val seenScenarios = mutableSetOf<StartingScenarioId>()
          val limit = minOf(suiteSize, uniqueScenarioCount)
          while (seenScenarios.size < limit) {
            val scenarioId = scenarioIds[rng.nextInt(scenarioIds.size)]
            if (seenScenarios.add(scenarioId)) {
              scenarioKills[scenarioId]?.let { kills ->
                killed.addAll(
                    if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
              }
            }
          }
          killed.size
        }
        .collect(Collectors.toList())
  }

  private fun evaluateRandomDrawScenarioWithReplacement(
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
          val rng = Random(42L + rep)
          val alreadyUsedPerGroup = Array(groups.size) { mutableSetOf<StartingScenarioId>() }
          val activeGroups = (0 until groups.size).shuffled(rng).toMutableList()
          val killed = mutableSetOf<MutantId>()
          var drawn = 0
          var pos = 0

          while (drawn < suiteSize && activeGroups.isNotEmpty()) {
            val groupIdx = activeGroups[pos]
            val alreadyUsed = alreadyUsedPerGroup[groupIdx]

            if (alreadyUsed.size >= uniqueScenarioCountPerGroup[groupIdx]) {
              activeGroups.removeAt(pos)
              if (activeGroups.isEmpty()) break
              pos %= activeGroups.size
              continue
            }

            val scenarioIds = scenarioIdListsPerGroup[groupIdx]
            var scenarioId: StartingScenarioId
            do {
              scenarioId = scenarioIds[rng.nextInt(scenarioIds.size)]
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
   * Draws unique starting scenarios from the tick-weighted [scenarioIdPool] without replacement
   * until one of [killingScenarios] is first encountered. Returns one draw count per repetition; -1
   * indicates pool exhaustion without a kill.
   */
  private fun evaluateTimeToKillRandom(
      scenarioIdPool: List<Int>,
      killingScenarios: Set<Int>,
  ): List<Int> {
    val uniqueScenarioCount = scenarioIdPool.distinct().size
    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          val rng = Random(42L + rep)
          val seen = mutableSetOf<Int>()
          var killedAt = -1
          while (seen.size < uniqueScenarioCount) {
            val scenarioId = scenarioIdPool[rng.nextInt(scenarioIdPool.size)]
            if (seen.add(scenarioId)) {
              if (scenarioId in killingScenarios) {
                killedAt = seen.size
                break
              }
            }
          }
          killedAt
        }
        .collect(Collectors.toList())
  }

  /**
   * Cycles round-robin through shuffled [scenarioIdListsPerGroup], drawing one unique scenario per
   * group per slot without replacement, until a scenario in [killingScenarios] is encountered.
   * Returns one draw count per repetition; -1 indicates all groups were exhausted before any kill.
   *
   * @param uniqueScenarioCountPerGroup Pre-computed distinct scenario count per group.
   */
  private fun evaluateTimeToKillRoundRobin(
      scenarioIdListsPerGroup: List<List<Int>>,
      uniqueScenarioCountPerGroup: List<Int>,
      killingScenarios: Set<Int>,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val alreadyUsedPerGroup = Array(scenarioIdListsPerGroup.size) { mutableSetOf<Int>() }
            val activeGroups = (0 until scenarioIdListsPerGroup.size).shuffled(rng).toMutableList()
            var drawn = 0
            var pos = 0
            var killedAt = -1

            while (activeGroups.isNotEmpty()) {
              val groupIdx = activeGroups[pos]
              val alreadyUsed = alreadyUsedPerGroup[groupIdx]

              if (alreadyUsed.size >= uniqueScenarioCountPerGroup[groupIdx]) {
                activeGroups.removeAt(pos)
                if (activeGroups.isEmpty()) break
                pos %= activeGroups.size
                continue
              }

              val scenarioIds = scenarioIdListsPerGroup[groupIdx]
              var scenarioId: Int
              do {
                scenarioId = scenarioIds[rng.nextInt(scenarioIds.size)]
              } while (!alreadyUsed.add(scenarioId))

              drawn++
              if (scenarioId in killingScenarios) {
                killedAt = drawn
                break
              }
              pos = (pos + 1) % activeGroups.size
            }
            killedAt
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
