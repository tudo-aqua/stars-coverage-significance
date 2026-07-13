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
import tools.aqua.stars.coverage.significance.db.tables.ScenarioMutantKillCountView
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
    val dcLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>
    val accidentDCLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>
    val startingScenarioIdsPerDCLeafId: List<Set<StartingScenarioId>>
    val startingScenarioIdsPerAccidentDCLeafId: List<Set<StartingScenarioId>>
    val accidentStartingScenarioIdsPerAccidentDCLeafId: List<Set<StartingScenarioId>>
    val accidentScenarioIds: Set<StartingScenarioId>

    if (hasLeaf) {
      println("  Grouping ticks by DC leaf node...")
      val allTicksGroupedByDCLeafId =
          ticks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId!! }

      // Grouping of all ticks
      dcLeafGroups = allTicksGroupedByDCLeafId.values.toList()
      startingScenarioIdsPerDCLeafId =
          dcLeafGroups.map { databaseEntriesForDCLeafId ->
            databaseEntriesForDCLeafId.map { it.scenarioConfigId }.toSet()
          }

      saveBucketSize(startingScenarioIdsPerDCLeafId.map { it.size }, "bucket_size_per_dc_leaf")

      // Grouping of all ticks that lead to an accident
      accidentDCLeafGroups =
          dcLeafGroups.filter { group -> group.any { it.nextTickG0Failed == true } }
      println(
          "  ${accidentDCLeafGroups.size} of ${dcLeafGroups.size} DC leaf groups contain accidents.")
      startingScenarioIdsPerAccidentDCLeafId =
          accidentDCLeafGroups.map { databaseEntriesForAccidentDCLeafId ->
            databaseEntriesForAccidentDCLeafId.map { it.scenarioConfigId }.toSet()
          }

      saveBucketSize(
          startingScenarioIdsPerAccidentDCLeafId.map { it.size },
          "bucket_size_per_accident_dc_leaf")

      accidentScenarioIds =
          ScenarioMutantKillCountView.getAll()
              .filter { it.mutantsKilled > 0 }
              .map { it.scenarioConfigId }
              .toSet()

      accidentStartingScenarioIdsPerAccidentDCLeafId =
          startingScenarioIdsPerAccidentDCLeafId.map {
            it.filter { it in accidentScenarioIds }.toSet()
          }

      saveBucketSize(
          accidentStartingScenarioIdsPerAccidentDCLeafId.map { it.size },
          "bucket_size_per_accident_dc_leaf_accident_scenarios")
    } else {
      dcLeafGroups = emptyList()
      accidentDCLeafGroups = emptyList()
      startingScenarioIdsPerDCLeafId = emptyList()
      startingScenarioIdsPerAccidentDCLeafId = emptyList()
      accidentStartingScenarioIdsPerAccidentDCLeafId = emptyList()
      accidentScenarioIds = emptySet()
    }

    val allScenarioIds = ScenarioStartingConfigurationRepository.getAll().map { it.id!! }.toSet()

    return SamplingData(
        allTicks = ticks,
        dcLeafGroups = dcLeafGroups,
        accidentDCLeafGroups = accidentDCLeafGroups,
        allScenarioIds = allScenarioIds,
        accidentScenarioIds = accidentScenarioIds,
        startingScenarioIdsPerDCLeafId = startingScenarioIdsPerDCLeafId,
        startingScenarioIdsPerAccidentDCLeafId = startingScenarioIdsPerAccidentDCLeafId,
        accidentStartingScenarioIdsPerAccidentDCLeafId =
            accidentStartingScenarioIdsPerAccidentDCLeafId)
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
      println("      Scenario Pool: ${data.allScenarioIds.size} entries.")
      save(
          evaluateRandomDrawScenario(data.allScenarioIds, scenarioKills, suiteSize),
          "random_scenario",
          suiteSize)

      println("    Evaluating uniform-random accident scenario sampling.")
      println("      Scenario Pool: ${data.accidentScenarioIds.size} entries.")
      save(
          evaluateRandomDrawScenario(data.accidentScenarioIds, scenarioKills, suiteSize),
          "random_accident_scenario",
          suiteSize)

      if (data.startingScenarioIdsPerDCLeafId.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (including non-accident groups).")
        data.startingScenarioIdsPerDCLeafId.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        save(
            evaluateRoundRobinScenario(
                data.startingScenarioIdsPerDCLeafId, scenarioKills, suiteSize),
            "leaf_scenario",
            suiteSize)
      }

      if (data.startingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups only, including non-accident scenarios per group).")
        println(
            "      Accident DC Leaf Pool: ${data.startingScenarioIdsPerAccidentDCLeafId.size} entries.")
        data.startingScenarioIdsPerAccidentDCLeafId.forEachIndexed { index, ticks ->
          println("        Accident DC Leaf Group '$index': ${ticks.size} entries.")
        }
        save(
            evaluateRoundRobinScenario(
                data.startingScenarioIdsPerAccidentDCLeafId, scenarioKills, suiteSize),
            "leaf_scenario_accidents",
            suiteSize)
      }

      if (data.accidentStartingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
        println(
            "    Evaluating decision-tree-leaf-stratified accident scenario sampling (accident leaf groups only and accident scenarios only).")
        println(
            "      Accident DC Leaf Accident Scenario Pool: ${data.accidentStartingScenarioIdsPerAccidentDCLeafId.size} entries.")
        data.accidentStartingScenarioIdsPerAccidentDCLeafId.forEachIndexed { index, ticks ->
          println("      Accident DC Leaf Accident-Scenario Group '$index': ${ticks.size} entries.")
        }
        save(
            evaluateRoundRobinScenario(
                data.accidentStartingScenarioIdsPerAccidentDCLeafId, scenarioKills, suiteSize),
            "leaf_accident-scenario_accidents",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random scenario sampling (rare mutants).")
        println("      Scenario Pool: ${data.allScenarioIds.size} entries.")
        save(
            evaluateRandomDrawScenario(
                data.allScenarioIds, scenarioKills, suiteSize, rareMutantIds),
            "random_scenario_rare",
            suiteSize)

        println(
            "    Evaluating uniform-random scenario sampling (rare mutants only accident scenarios).")
        println("      Scenario Pool: ${data.accidentScenarioIds.size} entries.")
        save(
            evaluateRandomDrawScenario(
                data.accidentScenarioIds, scenarioKills, suiteSize, rareMutantIds),
            "random_accident-scenarios_rare",
            suiteSize)

        if (data.startingScenarioIdsPerDCLeafId.isNotEmpty()) {
          println("    Evaluating decision-tree-leaf-stratified scenario sampling (rare mutants).")
          data.startingScenarioIdsPerDCLeafId.forEachIndexed { index, ticks ->
            println("      DC Leaf Group '$index': ${ticks.size} entries.")
          }
          save(
              evaluateRoundRobinScenario(
                  data.startingScenarioIdsPerDCLeafId, scenarioKills, suiteSize, rareMutantIds),
              "leaf_scenario_rare",
              suiteSize)
        }

        if (data.startingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified scenario sampling (accident leaf groups, rare mutants).")
          data.startingScenarioIdsPerAccidentDCLeafId.forEachIndexed { index, ticks ->
            println("      Accident DC Leaf Group '$index': ${ticks.size} entries.")
          }
          save(
              evaluateRoundRobinScenario(
                  data.startingScenarioIdsPerAccidentDCLeafId,
                  scenarioKills,
                  suiteSize,
                  rareMutantIds),
              "leaf_scenario_accidents_rare",
              suiteSize)
        }

        if (data.accidentStartingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
          println(
              "    Evaluating decision-tree-leaf-stratified accident scenario sampling (accident leaf groups, only accident-scenarios, rare mutants).")
          println(
              "      Accident DC Leaf Accident Scenario Pool: ${data.accidentStartingScenarioIdsPerAccidentDCLeafId.size} entries.")
          data.accidentStartingScenarioIdsPerAccidentDCLeafId.forEachIndexed { index, ticks ->
            println(
                "      Accident DC Leaf Accident-Scenario Group '$index': ${ticks.size} entries.")
          }
          save(
              evaluateRoundRobinScenario(
                  data.accidentStartingScenarioIdsPerAccidentDCLeafId,
                  scenarioKills,
                  suiteSize,
                  rareMutantIds),
              "leaf_accident-scenario_accidents_rare",
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
    val killingScenariosByMutant: Map<MutantId, Set<StartingScenarioId>> =
        allViolations
            .filter { it.anyG0Violation }
            .groupBy { it.mutantId }
            .mapValues { (_, values) -> values.map { it.scenarioConfigId }.toSet() }
    val accidentMutantIds = killingScenariosByMutant.keys.sorted()
    println("  Found ${accidentMutantIds.size} mutants that cause accidents.")

    val data = buildSamplingData(allTicks, hasLeaf = fullRunId != null)

    for (mutantId in accidentMutantIds) {
      println("    Evaluating random time-to-kill.")
      println("      Full Scenario ID Pool: ${data.allScenarioIds.size} entries.")
      saveTimeToKill(
          evaluateTimeToKillRandom(data.allScenarioIds, killingScenariosByMutant, mutantId),
          "random",
          mutantId)

      println("    Evaluating random accident-scenarios only time-to-kill.")
      println("      Accident Scenario ID Pool: ${data.accidentScenarioIds.size} entries.")
      saveTimeToKill(
          evaluateTimeToKillRandom(data.accidentScenarioIds, killingScenariosByMutant, mutantId),
          "random_accident-scenarios",
          mutantId)

      if (data.startingScenarioIdsPerDCLeafId.isNotEmpty()) {
        println("    Evaluating leaf-stratified time-to-kill.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(
                data.startingScenarioIdsPerDCLeafId,
                killingScenariosByMutant,
                mutantId,
            ),
            "leaf",
            mutantId)
      }

      if (data.startingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
        println("    Evaluating accident-leaf-stratified time-to-kill.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(
                data.startingScenarioIdsPerAccidentDCLeafId, killingScenariosByMutant, mutantId),
            "leaf_accidents",
            mutantId)
      }

      if (data.accidentStartingScenarioIdsPerAccidentDCLeafId.isNotEmpty()) {
        println("    Evaluating accident-leaf-accident-scenario-stratified time-to-kill.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobin(
                data.accidentStartingScenarioIdsPerAccidentDCLeafId,
                killingScenariosByMutant,
                mutantId),
            "leaf_accidents_accident-scenarios",
            mutantId)
      }
    }

    println("Finished BaselineNextTickPostEvaluation (time to kill).")
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
  ): Map<StartingScenarioId, Set<MutantId>> =
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
      scenarioKills: Map<StartingScenarioId, Set<MutantId>>,
      suiteSize: Int,
      rareMutantIds: Set<MutantId>? = null,
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            pool
                .shuffled(Random(42L + rep))
                .subList(0, suiteSize)
                .flatMap {
                  scenarioKills[it]?.let { kills ->
                    if (rareMutantIds != null) kills.filter { kill -> kill in rareMutantIds }
                    else kills
                  } ?: emptyList()
                }
                .toSet()
                .size
          }
          .collect(Collectors.toList())

  private fun evaluateRoundRobinScenario(
      scenarioIdSetsPerGroup: List<Set<StartingScenarioId>>,
      scenarioKills: Map<StartingScenarioId, Set<MutantId>>,
      suiteSize: Int,
      rareMutantIds: Set<MutantId>? = null,
  ): List<Int> {
    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          val rng = Random(42L + rep)
          val workingLists =
              scenarioIdSetsPerGroup
                  .map { set -> set.shuffled(rng).toMutableList() }
                  .shuffled(rng)
                  .toMutableList()
          val killed = mutableSetOf<MutantId>()
          var drawn = 0
          var pos = 0
          while (workingLists.isNotEmpty() && drawn < suiteSize) {
            val currentGroup = workingLists[pos]
            val scenarioId = currentGroup.removeFirst()
            // Track current scenario
            drawn++
            // Remove drawn scenario from all groups
            workingLists.forEach { it.remove(scenarioId) }
            val emptyBeforeAndUntilPos = (0..pos).count { workingLists[it].isEmpty() }
            // Remove all empty groups
            workingLists.removeAll { it.isEmpty() }
            scenarioKills[scenarioId]?.let { kills ->
              killed.addAll(
                  if (rareMutantIds != null) kills.filter { it in rareMutantIds } else kills)
            }
            if (workingLists.isEmpty()) break
            pos = (pos - emptyBeforeAndUntilPos + 1) % workingLists.size
          }
          killed.size
        }
        .collect(Collectors.toList())
  }

  private fun evaluateTimeToKillRandom(
      scenarioIdPool: Set<StartingScenarioId>,
      killingScenarios: Map<MutantId, Set<StartingScenarioId>>,
      mutantIdToKill: MutantId
  ): List<Int> {
    return (0..REPETITIONS)
        .toList()
        .parallelStream()
        .map { rep ->
          val rng = Random(42L + rep)
          var seen = 0
          val pool = scenarioIdPool.shuffled(rng).toMutableList()
          while (pool.isNotEmpty()) {
            val scenarioId = pool.removeFirst()
            seen++
            if (killingScenarios[mutantIdToKill]?.contains(scenarioId) == true) {
              return@map seen
            }
          }
          -1
        }
        .collect(Collectors.toList())
  }

  private fun evaluateTimeToKillRoundRobin(
      scenarioIdListsPerGroup: List<Set<StartingScenarioId>>,
      killingScenariosByMutant: Map<MutantId, Set<StartingScenarioId>>,
      mutantId: MutantId
  ): List<Int> =
      (0..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingLists =
                scenarioIdListsPerGroup
                    .map { it.shuffled(rng).toMutableList() }
                    .shuffled(rng)
                    .toMutableList()
            var pos = 0
            var drawn = 0
            while (workingLists.isNotEmpty()) {
              val currentGroup = workingLists[pos]
              val scenarioId = currentGroup.removeFirst()
              // Track current scenario
              drawn++
              // Remove drawn scenario from all groups
              workingLists.forEach { it.remove(scenarioId) }
              val emptyBeforeAndUntilPos = (0..pos).count { workingLists[it].isEmpty() }
              // Remove all empty groups
              workingLists.removeAll { it.isEmpty() }
              if (killingScenariosByMutant[mutantId]?.contains(scenarioId) == true) {
                return@map drawn
              }
              if (workingLists.isEmpty()) break
              pos = (pos - emptyBeforeAndUntilPos + 1) % workingLists.size
            }
            -1
          }
          .collect(Collectors.toList())

  private fun saveBucketSize(bucketToSize: List<Int>, identifier: String) {
    val path = BASE_PATH.resolve("bucket_size/${identifier}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        "bucket_id; scenarios\n${bucketToSize.mapIndexed { index, value -> "${index + 1} $value\n" }}")
    println("    CSV written to: $path")
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
