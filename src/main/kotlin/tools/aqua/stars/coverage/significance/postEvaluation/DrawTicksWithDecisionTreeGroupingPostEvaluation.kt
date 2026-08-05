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
import org.jetbrains.exposed.dao.id.EntityID
import tools.aqua.stars.coverage.significance.MAX_RARE_MUTANT_FAILURES
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DecisionTreeLeafId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.SamplingDataTickDrawing

/**
 * Post-evaluation that simulates test suites by sampling individual ticks directly from
 * [tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable] and measuring how
 * many distinct mutants are killed, i.e. have at least one drawn tick where
 * [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true`.
 *
 * Three sampling strategies are evaluated, matching the three estimators on the significance card
 * of `/decision_tree_comparison/index.html`'s [tools.aqua.stars.coverage.significance] dashboard
 * (see that file's `buildSignificanceRows`/`computeLeafWeights` for the reference definitions this
 * mirrors):
 * 1. **Uniform random** from the full tick pool — corresponds to E(k/N).
 * 2. **DC-leaf round-robin**, cycling leaf groups with equal probability regardless of leaf size —
 *    corresponds to E(equal).
 * 3. **DC-leaf weighted**, picking a leaf at each draw with probability proportional to that leaf's
 *    significance weight w_l (its share of the summed "any monitor failure" probability across all
 *    leaves) — corresponds to E(weight). A leaf with w_l = 0 (no failing ticks at all) is never
 *    drawn from, exactly as the E(weight) sampling policy assumes.
 *
 * All tick data is loaded from the database exactly once per evaluation call, then all groupings
 * and repetitions operate on the in-memory list.
 */
object DrawTicksWithDecisionTreeGroupingPostEvaluation {

  private val BASE_PATH =
      Path.of(POST_EVALUATION_BASE_DIR, "draw_ticks_with_decision_tree_grouping")

  private fun buildSamplingData(
      allTicks: List<NextTickPostEvaluationDatabaseEntry>
  ): SamplingDataTickDrawing {
    println("  Grouping ticks by DC leaf node...")
    val allTicksGroupedByDCLeafId =
        allTicks.filter { it.leafNodeId != null }.groupBy { it.leafNodeId!! }
    val anyKillProbabilityByLeaf =
        allTicksGroupedByDCLeafId.mapValues { (_, ticks) ->
          ticks.count { it.nextTickG0Failed == true }.toDouble() / ticks.size
        }
    val sumP = anyKillProbabilityByLeaf.values.sum()
    val dtGroupWeights =
        anyKillProbabilityByLeaf.mapValues { (_, p) -> if (sumP > 0) p / sumP else 0.0 }
    return SamplingDataTickDrawing(
        allTicks = allTicks, dtLeafGroups = allTicksGroupedByDCLeafId, leafWeights = dtGroupWeights)
  }

  /**
   * For each suite size in [NEXT_TICK_SUITE_SIZES], measures how many distinct mutants each
   * sampling strategy kills across [REPETITIONS] repetitions, and writes one CSV per strategy/suite
   * size to `draw_ticks_with_decision_tree_grouping/size_<suiteSize>/`.
   */
  fun evaluate(decisionTreeRunId: EntityID<Int>? = null) {
    println("Starting DrawTicksWithDecisionTreeGroupingPostEvaluation.")

    val fullRunId = decisionTreeRunId ?: db { DecisionTreeRunsRepository.getLatestFullRunId() }
    if (decisionTreeRunId != null) {
      println("  Using given decision tree run ${decisionTreeRunId.value}.")
    } else if (fullRunId != null) {
      println("  Using leaf assignments from full run ${fullRunId.value}.")
    } else {
      error("  No full run (train_fraction=1.0) found - leaf strategies will be skipped.")
    }

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations(forRunId = fullRunId) }
    println("  Loaded ${allTicks.size} ticks.")

    println("  Calculating rare mutants from all ticks")
    val rareMutantIds = buildRareMutantIds(allTicks)
    println("  Found ${rareMutantIds.size} rare mutants (<= $MAX_RARE_MUTANT_FAILURES violations).")

    val data = buildSamplingData(allTicks)
    val leafGroups = data.dtLeafGroups.values.toList()

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize:")

      println("    Evaluating uniform-random tick sampling.")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      save(evaluateRandomDrawTicks(data.allTicks, suiteSize), "random_tick", suiteSize)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating DC-leaf round-robin tick sampling.")
        leafGroups.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        save(evaluateRoundRobinTicks(leafGroups, suiteSize), "leaf_tick", suiteSize)

        println("    Evaluating DC-leaf weighted tick sampling.")
        save(
            evaluateWeightedDrawTicks(data.dtLeafGroups, data.leafWeights, suiteSize),
            "leaf_tick_weighted",
            suiteSize)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random tick sampling (rare mutants).")
        save(
            evaluateRandomDrawTicks(data.allTicks, suiteSize, rareMutantIds),
            "random_tick_rare",
            suiteSize)

        if (leafGroups.isNotEmpty()) {
          println("    Evaluating DC-leaf round-robin tick sampling (rare mutants).")
          save(
              evaluateRoundRobinTicks(leafGroups, suiteSize, rareMutantIds),
              "leaf_tick_rare",
              suiteSize)

          println("    Evaluating DC-leaf weighted tick sampling (rare mutants).")
          save(
              evaluateWeightedDrawTicks(
                  data.dtLeafGroups, data.leafWeights, suiteSize, rareMutantIds),
              "leaf_tick_weighted_rare",
              suiteSize)
        }
      }
    }

    println("Finished DrawTicksWithDecisionTreeGroupingPostEvaluation.")
  }

  /**
   * For each mutant that causes at least one G0 accident, measures how many ticks each sampling
   * strategy draws (without replacement) before that mutant is first killed. Each of the
   * [REPETITIONS] repetitions produces one draw count; -1 is recorded if the entire pool was
   * exhausted without a kill. Results are written to
   * `draw_ticks_with_decision_tree_grouping/time_to_kill/mutant_<id>/ttk_<strategy>.csv`.
   */
  fun evaluateTimeToKill(decisionTreeRunId: EntityID<Int>? = null) {
    println("Starting DrawTicksWithDecisionTreeGroupingPostEvaluation (time to kill).")

    val fullRunId = decisionTreeRunId ?: db { DecisionTreeRunsRepository.getLatestFullRunId() }
    if (decisionTreeRunId != null) {
      println("  Using given decision tree run ${decisionTreeRunId.value}.")
    } else if (fullRunId != null) {
      println("  Using leaf assignments from full run ${fullRunId.value}.")
    } else {
      error("  No full run (train_fraction=1.0) found - leaf strategies will be skipped.")
    }

    println("  Loading tick data into memory (this may take several minutes)...")
    val allTicks = db { buildTickWiseNextTickMonitorViolations(forRunId = fullRunId) }
    println("  Loaded ${allTicks.size} ticks.")

    val data = buildSamplingData(allTicks)
    val leafGroups = data.dtLeafGroups.values.toList()

    val accidentMutantIds =
        allTicks.filter { it.nextTickG0Failed == true }.map { it.mutantId }.toSortedSet()
    println("  Found ${accidentMutantIds.size} mutants that cause accidents.")

    for (mutantId in accidentMutantIds) {
      println("    Evaluating random time-to-kill for mutant $mutantId.")
      saveTimeToKill(
          evaluateTimeToKillRandomTicks(data.allTicks, mutantId), "random_tick", mutantId)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating DC-leaf round-robin time-to-kill for mutant $mutantId.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobinTicks(leafGroups, mutantId), "leaf_tick", mutantId)

        println("    Evaluating DC-leaf weighted time-to-kill for mutant $mutantId.")
        saveTimeToKill(
            evaluateTimeToKillWeightedTicks(data.dtLeafGroups, data.leafWeights, mutantId),
            "leaf_tick_weighted",
            mutantId)
      }
    }

    println("Finished DrawTicksWithDecisionTreeGroupingPostEvaluation (time to kill).")
  }

  private fun buildRareMutantIds(
      allTicks: List<NextTickPostEvaluationDatabaseEntry>
  ): Set<MutantId> =
      allTicks
          .filter { it.nextTickG0Failed ?: false }
          .groupBy { it.mutantId }
          .filterValues { it.size in 1..MAX_RARE_MUTANT_FAILURES }
          .keys
          .toSet()

  // ------------------------------------------------------- suite-size sweep strategies

  private fun evaluateRandomDrawTicks(
      ticks: List<NextTickPostEvaluationDatabaseEntry>,
      suiteSize: Int,
      rareMutantIds: Set<MutantId>? = null,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            randomIndices(ticks.size, suiteSize, Random(42L + rep))
                .map { ticks[it] }
                .killedMutants(rareMutantIds)
                .size
          }
          .collect(Collectors.toList())

  /**
   * Returns [k] distinct indices drawn uniformly at random from `0 until n`, via Floyd's algorithm
   * for sampling without replacement. Runs in O(k) time and space regardless of [n].
   */
  private fun randomIndices(n: Int, k: Int, rng: Random): List<Int> {
    val picked = LinkedHashSet<Int>(k * 2)
    for (i in (n - k) until n) {
      val t = rng.nextInt(i + 1)
      if (!picked.add(t)) picked.add(i)
    }
    return picked.toList()
  }

  /**
   * Only the leaf-group *order* is shuffled up front (a few hundred entries at most - cheap). Each
   * leaf's own tick list is kept unshuffled and drawn from via
   * [MutableList.drawAndRemoveRandomTick] instead, since `it.shuffled(rng)` per leaf would permute
   * the full tick pool (hundreds of millions of entries, summed across leaves) even though a suite
   * draws only [suiteSize] of them.
   */
  private fun evaluateRoundRobinTicks(
      ticksPerLeaf: List<List<NextTickPostEvaluationDatabaseEntry>>,
      suiteSize: Int,
      rareMutantIds: Set<MutantId>? = null,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingLists = ticksPerLeaf.map { it.toMutableList() }.shuffled(rng).toMutableList()
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            var pos = 0
            while (workingLists.isNotEmpty() && drawn < suiteSize) {
              val currentGroup = workingLists[pos]
              val tick = currentGroup.drawAndRemoveRandomTick(rng)
              drawn++
              if (currentGroup.isEmpty()) {
                workingLists.removeAt(pos)
                // removeAt(pos) shifts every later group left by one, so pos already points at
                // the next group "for free" - unless pos was the last index, in which case pos
                // now equals the new size (one past the end). Wrapping via modulo is a no-op in
                // the first case and correctly wraps to 0 in the second.
                if (workingLists.isNotEmpty()) pos %= workingLists.size
              } else {
                pos = (pos + 1) % workingLists.size
              }
              tick.killingMutantOrNull(rareMutantIds)?.let { killed.add(it) }
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Weighted-leaf variant of [evaluateRoundRobinTicks]: instead of cycling leaves in a fixed order,
   * each draw picks a leaf via [weightedPickLeaf] (probability proportional to [leafWeights],
   * renormalized over leaves that still have ticks left), then draws one random tick from that leaf
   * via [MutableList.drawAndRemoveRandomTick] (no per-leaf `shuffled()` - same reasoning as
   * [evaluateRoundRobinTicks]). This is the sampling process the significance card's E(weight) = 1
   * / Σ(p_l·w_l) estimator assumes.
   */
  private fun evaluateWeightedDrawTicks(
      ticksPerLeaf: Map<DecisionTreeLeafId, List<NextTickPostEvaluationDatabaseEntry>>,
      leafWeights: Map<DecisionTreeLeafId, Double>,
      suiteSize: Int,
      rareMutantIds: Set<MutantId>? = null,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingLists = ticksPerLeaf.mapValues { (_, ticks) -> ticks.toMutableList() }
            val candidateLeafIds =
                workingLists.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            while (candidateLeafIds.isNotEmpty() && drawn < suiteSize) {
              val leafId = weightedPickLeaf(candidateLeafIds, leafWeights, rng)
              val leafTicks = workingLists.getValue(leafId)
              val tick = leafTicks.drawAndRemoveRandomTick(rng)
              drawn++
              if (leafTicks.isEmpty()) candidateLeafIds.remove(leafId)
              tick.killingMutantOrNull(rareMutantIds)?.let { killed.add(it) }
            }
            killed.size
          }
          .collect(Collectors.toList())

  // ------------------------------------------------------------------- time-to-kill strategies

  /** Draws ticks one at a time without replacement until [mutantId] is killed. */
  private fun evaluateTimeToKillRandomTicks(
      tickPool: List<NextTickPostEvaluationDatabaseEntry>,
      mutantId: MutantId,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val remaining = tickPool.toMutableList()
            var seen = 0
            while (remaining.isNotEmpty()) {
              val tick = remaining.drawAndRemoveRandomTick(rng)
              seen++
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map seen
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Round-robin variant of [evaluateTimeToKillWeightedTicks]'s no-inner-shuffle approach - see
   * [evaluateRoundRobinTicks].
   */
  private fun evaluateTimeToKillRoundRobinTicks(
      ticksPerLeaf: List<List<NextTickPostEvaluationDatabaseEntry>>,
      mutantId: MutantId,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingLists = ticksPerLeaf.map { it.toMutableList() }.shuffled(rng).toMutableList()
            var pos = 0
            var drawn = 0
            while (workingLists.isNotEmpty()) {
              val currentGroup = workingLists[pos]
              val tick = currentGroup.drawAndRemoveRandomTick(rng)
              drawn++
              if (currentGroup.isEmpty()) {
                workingLists.removeAt(pos)
                if (workingLists.isNotEmpty()) pos %= workingLists.size
              } else {
                pos = (pos + 1) % workingLists.size
              }
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map drawn
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Weighted-leaf variant of [evaluateTimeToKillRandomTicks]: no per-leaf `shuffled()` either -
   * each leaf's list is copied as-is and [MutableList.drawAndRemoveRandomTick] draws one random
   * element from it on demand, so a kill found early doesn't pay for permuting leaves that were
   * never visited.
   */
  private fun evaluateTimeToKillWeightedTicks(
      ticksPerLeaf: Map<DecisionTreeLeafId, List<NextTickPostEvaluationDatabaseEntry>>,
      leafWeights: Map<DecisionTreeLeafId, Double>,
      mutantId: MutantId,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingLists = ticksPerLeaf.mapValues { (_, ticks) -> ticks.toMutableList() }
            val candidateLeafIds =
                workingLists.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()
            var drawn = 0
            while (candidateLeafIds.isNotEmpty()) {
              val leafId = weightedPickLeaf(candidateLeafIds, leafWeights, rng)
              val leafTicks = workingLists.getValue(leafId)
              val tick = leafTicks.drawAndRemoveRandomTick(rng)
              drawn++
              if (leafTicks.isEmpty()) candidateLeafIds.remove(leafId)
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map drawn
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Picks one entry from [candidateLeafIds] via a weighted random choice, probability proportional
   * to [leafWeights] but renormalized over just the candidates passed in - so a leaf that's been
   * fully drawn from can be dropped from the candidate list between draws without needing to touch
   * any other leaf's weight.
   */
  private fun weightedPickLeaf(
      candidateLeafIds: List<DecisionTreeLeafId>,
      leafWeights: Map<DecisionTreeLeafId, Double>,
      rng: Random,
  ): DecisionTreeLeafId {
    val total = candidateLeafIds.sumOf { leafWeights.getValue(it) }
    var r = rng.nextDouble() * total
    for (leafId in candidateLeafIds) {
      r -= leafWeights.getValue(leafId)
      if (r <= 0.0) return leafId
    }
    return candidateLeafIds.last()
  }

  /**
   * Removes and returns one uniformly random element from this list, via swap-remove (overwrite the
   * drawn slot with the last element, then drop the last slot) - O(1), unlike
   * `removeAt(randomIndex)`, which would shift every following element on every draw. Order doesn't
   * matter for the swap since the draw itself is already uniformly random.
   */
  private fun <T> MutableList<T>.drawAndRemoveRandomTick(rng: Random): T {
    val idx = rng.nextInt(size)
    val value = this[idx]
    this[idx] = this[size - 1]
    removeAt(size - 1)
    return value
  }

  /**
   * The mutant a single tick killed, or `null` if it wasn't a failing tick (or [rareMutantIds]
   * excludes it).
   */
  private fun NextTickPostEvaluationDatabaseEntry.killingMutantOrNull(
      rareMutantIds: Set<MutantId>?
  ): MutantId? =
      mutantId.takeIf {
        nextTickG0Failed == true && (rareMutantIds == null || mutantId in rareMutantIds)
      }

  private fun List<NextTickPostEvaluationDatabaseEntry>.killedMutants(
      rareMutantIds: Set<MutantId>?
  ): Set<MutantId> = mapNotNull { it.killingMutantOrNull(rareMutantIds) }.toSet()

  /**
   * Saves [results] as a single-column CSV under `[BASE_PATH]/size_<suiteSize>/`.
   *
   * @param results One killed-mutant count per repetition.
   * @param identifier Sampling strategy label used in the file name (e.g. `"random_tick"`).
   * @param suiteSize Suite size used for this evaluation run, determines the subfolder.
   */
  private fun save(results: List<Int>, identifier: String, suiteSize: Int) {
    val path = BASE_PATH.resolve("size_$suiteSize/draw_ticks_${identifier}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(prefix = "Mutants killed ${identifier}\n", separator = "\n") {
          it.toString()
        })
    println("    CSV written to: $path")
  }

  private fun saveTimeToKill(results: List<Int>, strategy: String, mutantId: Int) {
    val path = BASE_PATH.resolve("time_to_kill/mutant_${mutantId}/ttk_${strategy}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(prefix = "draws_to_kill\n", separator = "\n") { it.toString() })
    println("    CSV written to: $path")
  }
}
