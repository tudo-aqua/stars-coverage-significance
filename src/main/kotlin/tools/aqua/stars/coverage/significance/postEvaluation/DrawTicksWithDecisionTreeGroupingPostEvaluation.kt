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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.dao.id.EntityID
import tools.aqua.stars.coverage.significance.MAX_RARE_MUTANT_FAILURES
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.tables.DtLeafMutantTickCountsView
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DecisionTreeLeafId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.SamplingDataTickDrawing
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration

/**
 * Post-evaluation that simulates test suites by sampling individual ticks directly from
 * [tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable] and measuring how
 * many distinct mutants are killed, i.e. have at least one drawn tick where
 * [NextTickPostEvaluationDatabaseEntry.nextTickG0Failed] is `true`.
 *
 * Four sampling strategies are evaluated by [evaluate]/[evaluateTimeToKill]. The first three match
 * the three estimators [exportSignificance] computes (see that function and `computeSignificance`
 * in `time_to_kill_comparison/index.html`, which consumes its output, for the reference definitions
 * these mirror):
 * 1. **Uniform random** from the full tick pool — corresponds to E(k/N).
 * 2. **DC-leaf round-robin**, cycling leaf groups with equal probability regardless of leaf size —
 *    corresponds to E(equal).
 * 3. **DC-leaf weighted**, picking a leaf at each draw with probability proportional to that leaf's
 *    significance weight w_l (its share of the summed "any monitor failure" probability across all
 *    leaves) — corresponds to E(weight). A leaf with w_l = 0 (no failing ticks at all) is never
 *    drawn from, exactly as the E(weight) sampling policy assumes.
 * 4. **DC-leaf alternating**, no estimator counterpart: alternates draw-by-draw between the
 *    round-robin (2) and weighted (3) policies above, sharing the same depleting tick pool between
 *    them. If the policy whose turn it is has nothing left to draw from (its leaves are all empty,
 *    which for the weighted policy also includes every w_l = 0 leaf), that turn falls back to the
 *    other policy instead of being skipped, so the alternation only truly stops once the pool
 *    itself is exhausted.
 *
 * All tick data is loaded from the database exactly once per [evaluate]/[evaluateTimeToKill] call,
 * then all groupings and repetitions operate on that same in-memory list (or, for leaf-based
 * strategies, that same per-leaf grouping of it) - shared, read-only, across every repetition and
 * every leaf. No repetition ever copies it: [SharedDrawPool] draws real ticks from it without
 * replacement by replaying a swap-remove shuffle through a small per-pool overlay instead of an
 * actual copy, so [REPETITIONS] repetitions running in parallel cost [REPETITIONS] times the
 * *draws*, not [REPETITIONS] times the pool size. Calling [evaluate] and [evaluateTimeToKill]
 * separately for the same run still means two loads, though - [evaluateAll] is the one-load
 * entry point that runs both (plus [exportSignificance]) against a single shared load, and is
 * what [tools.aqua.stars.coverage.significance.RunDrawTicksWithDecisionTreeGrouping] uses.
 */
object DrawTicksWithDecisionTreeGroupingPostEvaluation {

  /**
   * Every output of a call is scoped under its own `run_<runId>/` folder (`runId` being the
   * decision tree run whose leaf assignments were used), so that repeated evaluations against
   * different decision tree runs land in separate folders instead of overwriting/mixing each
   * other's `size_<n>/` and `time_to_kill/` output.
   */
  private fun basePath(runId: Int): Path =
      Path.of(POST_EVALUATION_BASE_DIR, "draw_ticks_with_decision_tree_grouping", "run_$runId")

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
   * Resolves [decisionTreeRunId] (or, if `null`, the latest full run) to the `(resolvedRunId,
   * fullRunId)` pair every public function here needs, logging which one was picked exactly as
   * each used to inline. Shared so [evaluateAll] can resolve it once instead of twice.
   */
  private fun resolveRunId(decisionTreeRunId: EntityID<Int>?): Pair<Int, EntityID<Int>?> {
    val fullRunId = decisionTreeRunId ?: db { DecisionTreeRunsRepository.getLatestFullRunId() }
    val resolvedRunId: Int =
        fullRunId?.value
            ?: error("  No full run (train_fraction=1.0) found - leaf strategies will be skipped.")
    if (decisionTreeRunId != null) {
      println("  Using given decision tree run $resolvedRunId.")
    } else {
      println("  Using leaf assignments from full run $resolvedRunId.")
    }
    return resolvedRunId to fullRunId
  }

  /**
   * Loads every tick for [fullRunId] and groups it, exactly once - the single expensive step
   * [evaluate] and [evaluateTimeToKill] used to each perform independently, even when
   * [evaluateAll] calls both for the very same run against the very same underlying rows.
   */
  private fun loadTicksAndSamplingData(
      fullRunId: EntityID<Int>?
  ): Pair<List<NextTickPostEvaluationDatabaseEntry>, SamplingDataTickDrawing> {
    println("  Loading tick data into memory (this may take several minutes)...")
    // Not wrapped in db {}/transaction {}: buildTickWiseNextTickMonitorViolations manages its own
    // transactions (parallel chunked load - see its KDoc).
    val allTicks = buildTickWiseNextTickMonitorViolations(forRunId = fullRunId)
    println("  Loaded ${allTicks.size} ticks.")
    return allTicks to buildSamplingData(allTicks)
  }

  /**
   * For each suite size in [NEXT_TICK_SUITE_SIZES], measures how many distinct mutants each
   * sampling strategy kills across [REPETITIONS] repetitions, and writes one CSV per strategy/suite
   * size to `draw_ticks_with_decision_tree_grouping/run_<runId>/size_<suiteSize>/`.
   */
  fun evaluate(decisionTreeRunId: EntityID<Int>? = null) {
    println("Starting DrawTicksWithDecisionTreeGroupingPostEvaluation.")
    val (resolvedRunId, fullRunId) = resolveRunId(decisionTreeRunId)
    val (allTicks, data) = loadTicksAndSamplingData(fullRunId)
    evaluateCore(resolvedRunId, allTicks, data)
    println("Finished DrawTicksWithDecisionTreeGroupingPostEvaluation.")
  }

  /**
   * Suite-size sweep body shared by [evaluate] (which loads [allTicks]/[data] itself) and
   * [evaluateAll] (which loads them once and passes them to both this and
   * [evaluateTimeToKillCore]).
   */
  private fun evaluateCore(
      resolvedRunId: Int,
      allTicks: List<NextTickPostEvaluationDatabaseEntry>,
      data: SamplingDataTickDrawing,
  ) {
    println("  Calculating rare mutants from all ticks")
    val rareMutantIds = buildRareMutantIds(allTicks)
    println("  Found ${rareMutantIds.size} rare mutants (<= $MAX_RARE_MUTANT_FAILURES violations).")

    val leafGroups = data.dtLeafGroups.values.toList()

    for (suiteSize in NEXT_TICK_SUITE_SIZES) {
      println("  Suite size $suiteSize:")

      println("    Evaluating uniform-random tick sampling.")
      println("      Tick Pool: ${data.allTicks.size} entries.")
      save(
          evaluateRandomDrawTicks(data.allTicks, suiteSize),
          "random_tick",
          suiteSize,
          resolvedRunId)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating DC-leaf round-robin tick sampling.")
        leafGroups.forEachIndexed { index, ticks ->
          println("      DC Leaf Group '$index': ${ticks.size} entries.")
        }
        save(evaluateRoundRobinTicks(leafGroups, suiteSize), "leaf_tick", suiteSize, resolvedRunId)

        println("    Evaluating DC-leaf weighted tick sampling.")
        save(
            evaluateWeightedDrawTicks(data.dtLeafGroups, data.leafWeights, suiteSize),
            "leaf_tick_weighted",
            suiteSize,
            resolvedRunId)

        println("    Evaluating DC-leaf alternating (equal/weighted) tick sampling.")
        save(
            evaluateAlternatingDrawTicks(data.dtLeafGroups, data.leafWeights, suiteSize),
            "leaf_tick_alternating",
            suiteSize,
            resolvedRunId)
      }

      if (rareMutantIds.isNotEmpty()) {
        println("    Evaluating uniform-random tick sampling (rare mutants).")
        save(
            evaluateRandomDrawTicks(data.allTicks, suiteSize, rareMutantIds),
            "random_tick_rare",
            suiteSize,
            resolvedRunId)

        if (leafGroups.isNotEmpty()) {
          println("    Evaluating DC-leaf round-robin tick sampling (rare mutants).")
          save(
              evaluateRoundRobinTicks(leafGroups, suiteSize, rareMutantIds),
              "leaf_tick_rare",
              suiteSize,
              resolvedRunId)

          println("    Evaluating DC-leaf weighted tick sampling (rare mutants).")
          save(
              evaluateWeightedDrawTicks(
                  data.dtLeafGroups, data.leafWeights, suiteSize, rareMutantIds),
              "leaf_tick_weighted_rare",
              suiteSize,
              resolvedRunId)

          println(
              "    Evaluating DC-leaf alternating (equal/weighted) tick sampling (rare mutants).")
          save(
              evaluateAlternatingDrawTicks(
                  data.dtLeafGroups, data.leafWeights, suiteSize, rareMutantIds),
              "leaf_tick_alternating_rare",
              suiteSize,
              resolvedRunId)
        }
      }
    }
  }

  /**
   * For each mutant that causes at least one G0 accident, measures how many ticks each sampling
   * strategy draws (without replacement) before that mutant is first killed. Each of the
   * [REPETITIONS] repetitions produces one draw count; -1 is recorded if the entire pool was
   * exhausted without a kill. Results are written to
   * `draw_ticks_with_decision_tree_grouping/run_<runId>/time_to_kill/mutant_<id>/ttk_<strategy>.csv`.
   */
  fun evaluateTimeToKill(decisionTreeRunId: EntityID<Int>? = null) {
    println("Starting DrawTicksWithDecisionTreeGroupingPostEvaluation (time to kill).")
    val (resolvedRunId, fullRunId) = resolveRunId(decisionTreeRunId)
    val (allTicks, data) = loadTicksAndSamplingData(fullRunId)
    evaluateTimeToKillCore(resolvedRunId, allTicks, data)
    println("Finished DrawTicksWithDecisionTreeGroupingPostEvaluation (time to kill).")
  }

  /**
   * Time-to-kill body shared by [evaluateTimeToKill] (which loads [allTicks]/[data] itself) and
   * [evaluateAll] (which loads them once and passes them to both this and [evaluateCore]).
   */
  private fun evaluateTimeToKillCore(
      resolvedRunId: Int,
      allTicks: List<NextTickPostEvaluationDatabaseEntry>,
      data: SamplingDataTickDrawing,
  ) {
    val leafGroups = data.dtLeafGroups.values.toList()

    val accidentMutantIds =
        allTicks.filter { it.nextTickG0Failed == true }.map { it.mutantId }.toSortedSet()
    println("  Found ${accidentMutantIds.size} mutants that cause accidents.")

    for (mutantId in accidentMutantIds) {
      println("    Evaluating random time-to-kill for mutant $mutantId.")
      saveTimeToKill(
          evaluateTimeToKillRandomTicks(data.allTicks, mutantId),
          "random_tick",
          mutantId,
          resolvedRunId)

      if (leafGroups.isNotEmpty()) {
        println("    Evaluating DC-leaf round-robin time-to-kill for mutant $mutantId.")
        saveTimeToKill(
            evaluateTimeToKillRoundRobinTicks(leafGroups, mutantId),
            "leaf_tick",
            mutantId,
            resolvedRunId)

        println("    Evaluating DC-leaf weighted time-to-kill for mutant $mutantId.")
        saveTimeToKill(
            evaluateTimeToKillWeightedTicks(data.dtLeafGroups, data.leafWeights, mutantId),
            "leaf_tick_weighted",
            mutantId,
            resolvedRunId)

        println(
            "    Evaluating DC-leaf alternating (equal/weighted) time-to-kill for mutant $mutantId.")
        saveTimeToKill(
            evaluateTimeToKillAlternatingTicks(data.dtLeafGroups, data.leafWeights, mutantId),
            "leaf_tick_alternating",
            mutantId,
            resolvedRunId)
      }
    }
  }

  /**
   * Runs [exportSignificance], [evaluateTimeToKill] and [evaluate] against the same decision tree
   * run while loading and grouping the tick table only **once** and sharing it between the latter
   * two - unlike calling all three separately (as
   * [tools.aqua.stars.coverage.significance.RunDrawTicksWithDecisionTreeGrouping] used to), which
   * reloaded and regrouped the exact same multi-hundred-million-row table twice, once per call,
   * even though both read identical rows for the same run.
   */
  fun evaluateAll(decisionTreeRunId: EntityID<Int>? = null) {
    println(
        "Starting DrawTicksWithDecisionTreeGroupingPostEvaluation (significance + time-to-kill + suite-size sweep).")
    exportSignificance(decisionTreeRunId)

    val (resolvedRunId, fullRunId) = resolveRunId(decisionTreeRunId)
    val (allTicks, data) = loadTicksAndSamplingData(fullRunId)
    evaluateTimeToKillCore(resolvedRunId, allTicks, data)
    evaluateCore(resolvedRunId, allTicks, data)

    println(
        "Finished DrawTicksWithDecisionTreeGroupingPostEvaluation (significance + time-to-kill + suite-size sweep).")
  }

  /**
   * Exports the per-leaf bucket statistics needed to compute the E(k/N)/E(equal)/E(weight)/
   * E(alternating) significance estimators for [decisionTreeRunId] against the [evaluate]/
   * [evaluateTimeToKill] strategies above - the database-wide total tick count (N), the run's
   * learned leaf count (L), and per-leaf `{totalTicks, failingTicks, mutantKillingAmount}` (from
   * which every per-mutant p_lm, p_l and w_l used by the estimators is derived). Both aggregates
   * are computed entirely in SQL via [DtLeafMutantTickCountsView], so no per-tick rows are loaded
   * into the JVM - unlike [evaluate]/[evaluateTimeToKill], this runs in seconds regardless of table
   * size.
   *
   * Written to `draw_ticks_with_decision_tree_grouping/run_<runId>/significance.json`, alongside
   * that run's `size_<n>/` and `time_to_kill/` output, so a single run folder is self-sufficient
   * for the full expected-vs-actual comparison. This supersedes the former standalone
   * `DecisionTreeComparisonPostEvaluation`/`decision_tree_comparison.json` export, which computed
   * the same bucket data but kept it in a separate, run-id-unscoped file.
   */
  fun exportSignificance(decisionTreeRunId: EntityID<Int>? = null) {
    println("Starting DrawTicksWithDecisionTreeGroupingPostEvaluation (significance export).")

    val fullRunId = decisionTreeRunId ?: db { DecisionTreeRunsRepository.getLatestFullRunId() }
    val resolvedRunId: Int =
        fullRunId?.value
            ?: error("  No full run (train_fraction=1.0) found - cannot export significance data.")
    println("  Using decision tree run $resolvedRunId.")

    println("  Counting total ticks...")
    val totalTicks = MetricFailedMonitorsRepository.count()
    println("    Found $totalTicks total ticks.")

    val learnedNumLeaves = DecisionTreeRunsRepository.getById(resolvedRunId)?.learnedNumLeaves
    println("  Learned leaves for this run: ${learnedNumLeaves ?: "unknown"}.")

    println("  Aggregating per-leaf bucket totals in SQL...")
    val leafTotals = DtLeafMutantTickCountsView.getLeafBucketTotalsForRunId(resolvedRunId)
    val mutantKillingAmountByLeafId =
        DtLeafMutantTickCountsView.getLeafMutantFailureCountsForRunId(resolvedRunId)
            .groupBy { it.leafNodeId }
            .mapValues { (_, counts) -> counts.associate { it.mutantId to it.failingTicks } }
    val buckets =
        leafTotals.map { totals ->
          SignificanceLeafBucket(
              leafId = totals.leafNodeId,
              totalTicks = totals.totalTicks,
              failingTicks = totals.failingTicks,
              mutantKillingAmount = mutantKillingAmountByLeafId[totals.leafNodeId].orEmpty())
        }
    println("    Found ${buckets.size} leaves.")

    val export =
        SignificanceExport(
            runId = resolvedRunId,
            totalTicks = totalTicks,
            learnedNumLeaves = learnedNumLeaves,
            buckets = buckets)
    val path = basePath(resolvedRunId).resolve("significance.json")
    Files.createDirectories(path.parent)
    path.writeText(jsonConfiguration.encodeToString(export))
    println("    JSON written to: $path")

    println("Finished DrawTicksWithDecisionTreeGroupingPostEvaluation (significance export).")
  }

  /**
   * @property leafId Leaf node index.
   * @property totalTicks Total number of ticks assigned to this leaf.
   * @property failingTicks Number of ticks in this leaf where the next tick's G0 (Accidents)
   *   monitor fires.
   * @property mutantKillingAmount Per-mutant count of failing ticks within this leaf; only mutants
   *   that killed at least one tick in the leaf are present.
   */
  @Serializable
  private data class SignificanceLeafBucket(
      val leafId: DecisionTreeLeafId,
      val totalTicks: Long,
      val failingTicks: Long,
      val mutantKillingAmount: Map<MutantId, Long>,
  )

  /**
   * @property runId Decision tree run these buckets were computed against.
   * @property totalTicks N: total number of ticks recorded in `metric_failed_monitors`,
   *   database-wide (not scoped to [runId]).
   * @property learnedNumLeaves L: the actual number of leaves LightGBM produced for this run (not
   *   the Optuna-tuned target), or `null` if the run has no recorded value.
   * @property buckets Per-leaf bucket information for [runId].
   */
  @Serializable
  private data class SignificanceExport(
      val runId: Int,
      val totalTicks: Long,
      val learnedNumLeaves: Int?,
      val buckets: List<SignificanceLeafBucket>,
  )

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
   * leaf's own tick list is never copied: every repetition wraps the same shared, unmutated list in
   * a fresh [SharedDrawPool] (which draws from it via [SharedDrawPool.drawAndRemoveRandomTick]
   * without ever copying or shuffling it), so a suite of [suiteSize] draws costs [suiteSize] work -
   * not the hundreds of millions of entries the full tick pool may hold, summed across leaves.
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
            val workingPools = ticksPerLeaf.map { SharedDrawPool(it) }.shuffled(rng).toMutableList()
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            var pos = 0
            while (workingPools.isNotEmpty() && drawn < suiteSize) {
              val currentPool = workingPools[pos]
              val tick = currentPool.drawAndRemoveRandomTick(rng)
              drawn++
              if (currentPool.isEmpty()) {
                workingPools.removeAt(pos)
                // removeAt(pos) shifts every later pool left by one, so pos already points at the
                // next pool "for free" - unless pos was the last index, in which case pos now
                // equals the new size (one past the end). Wrapping via modulo is a no-op in the
                // first case and correctly wraps to 0 in the second.
                if (workingPools.isNotEmpty()) pos %= workingPools.size
              } else {
                pos = (pos + 1) % workingPools.size
              }
              tick.killingMutantOrNull(rareMutantIds)?.let { killed.add(it) }
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Weighted-leaf variant of [evaluateRoundRobinTicks]: instead of cycling leaves in a fixed order,
   * each draw picks a leaf via [weightedPickLeaf] (probability proportional to [leafWeights],
   * renormalized over leaves that still have ticks left), then draws one random tick from that
   * leaf's [SharedDrawPool] (no per-leaf copy or shuffle - same reasoning as
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
            val workingPools = ticksPerLeaf.mapValues { (_, ticks) -> SharedDrawPool(ticks) }
            val candidateLeafIds =
                workingPools.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()
            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            while (candidateLeafIds.isNotEmpty() && drawn < suiteSize) {
              val leafId = weightedPickLeaf(candidateLeafIds, leafWeights, rng)
              val leafPool = workingPools.getValue(leafId)
              val tick = leafPool.drawAndRemoveRandomTick(rng)
              drawn++
              if (leafPool.isEmpty()) candidateLeafIds.remove(leafId)
              tick.killingMutantOrNull(rareMutantIds)?.let { killed.add(it) }
            }
            killed.size
          }
          .collect(Collectors.toList())

  /**
   * Alternates draw-by-draw between the round-robin policy of [evaluateRoundRobinTicks] and the
   * weighted policy of [evaluateWeightedDrawTicks], both operating on the same depleting
   * `workingLists` pool (a draw made by one policy is no longer available to the other). Each turn
   * starts with its "own" policy (round-robin on even draw indices, weighted on odd ones) and only
   * falls back to the other policy if its own has nothing left to draw from - so a leaf pool
   * exhausted for one policy (e.g. every w_l = 0 leaf, which the weighted policy never visits) can
   * still be drained by the other, and the alternation only stops once every leaf is empty.
   */
  private fun evaluateAlternatingDrawTicks(
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
            val workingPools = ticksPerLeaf.mapValues { (_, ticks) -> SharedDrawPool(ticks) }
            val roundRobinOrder = workingPools.keys.shuffled(rng).toMutableList()
            var rrPos = 0
            val candidateLeafIds =
                workingPools.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()

            fun nextEqualLeafOrNull(): DecisionTreeLeafId? {
              while (roundRobinOrder.isNotEmpty() &&
                  workingPools.getValue(roundRobinOrder[rrPos % roundRobinOrder.size]).isEmpty()) {
                roundRobinOrder.removeAt(rrPos % roundRobinOrder.size)
              }
              if (roundRobinOrder.isEmpty()) return null
              val leafId = roundRobinOrder[rrPos % roundRobinOrder.size]
              rrPos = (rrPos + 1) % roundRobinOrder.size
              return leafId
            }

            fun nextWeightedLeafOrNull(): DecisionTreeLeafId? {
              candidateLeafIds.removeAll { workingPools.getValue(it).isEmpty() }
              return if (candidateLeafIds.isEmpty()) null
              else weightedPickLeaf(candidateLeafIds, leafWeights, rng)
            }

            var useEqualTurn = true
            fun nextLeafId(): DecisionTreeLeafId? {
              val leafId =
                  if (useEqualTurn) nextEqualLeafOrNull() ?: nextWeightedLeafOrNull()
                  else nextWeightedLeafOrNull() ?: nextEqualLeafOrNull()
              useEqualTurn = !useEqualTurn
              return leafId
            }

            val killed = mutableSetOf<MutantId>()
            var drawn = 0
            while (drawn < suiteSize) {
              val leafId = nextLeafId() ?: break
              val tick = workingPools.getValue(leafId).drawAndRemoveRandomTick(rng)
              drawn++
              tick.killingMutantOrNull(rareMutantIds)?.let { killed.add(it) }
            }
            killed.size
          }
          .collect(Collectors.toList())

  // ------------------------------------------------------------------- time-to-kill strategies

  /**
   * Draws ticks one at a time without replacement until [mutantId] is killed. [tickPool] is the
   * same shared, unmutated list every repetition reads from - see [SharedDrawPool] - so a kill
   * found after a handful of draws costs a handful of draws, not a copy of the full pool.
   */
  private fun evaluateTimeToKillRandomTicks(
      tickPool: List<NextTickPostEvaluationDatabaseEntry>,
      mutantId: MutantId,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val pool = SharedDrawPool(tickPool)
            var seen = 0
            while (!pool.isEmpty()) {
              val tick = pool.drawAndRemoveRandomTick(rng)
              seen++
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map seen
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Round-robin variant of [evaluateTimeToKillWeightedTicks]'s no-copy approach - see
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
            val workingPools = ticksPerLeaf.map { SharedDrawPool(it) }.shuffled(rng).toMutableList()
            var pos = 0
            var drawn = 0
            while (workingPools.isNotEmpty()) {
              val currentPool = workingPools[pos]
              val tick = currentPool.drawAndRemoveRandomTick(rng)
              drawn++
              if (currentPool.isEmpty()) {
                workingPools.removeAt(pos)
                if (workingPools.isNotEmpty()) pos %= workingPools.size
              } else {
                pos = (pos + 1) % workingPools.size
              }
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map drawn
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Weighted-leaf variant of [evaluateTimeToKillRandomTicks]: no per-leaf copy or shuffle either -
   * each leaf's list is wrapped as-is in a [SharedDrawPool], which draws one random element from it
   * on demand, so a kill found early doesn't pay for touching leaves that were never visited.
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
            val workingPools = ticksPerLeaf.mapValues { (_, ticks) -> SharedDrawPool(ticks) }
            val candidateLeafIds =
                workingPools.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()
            var drawn = 0
            while (candidateLeafIds.isNotEmpty()) {
              val leafId = weightedPickLeaf(candidateLeafIds, leafWeights, rng)
              val leafPool = workingPools.getValue(leafId)
              val tick = leafPool.drawAndRemoveRandomTick(rng)
              drawn++
              if (leafPool.isEmpty()) candidateLeafIds.remove(leafId)
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map drawn
            }
            -1
          }
          .collect(Collectors.toList())

  /**
   * Time-to-kill counterpart of [evaluateAlternatingDrawTicks] - see that function for the
   * alternation/fallback rules.
   */
  private fun evaluateTimeToKillAlternatingTicks(
      ticksPerLeaf: Map<DecisionTreeLeafId, List<NextTickPostEvaluationDatabaseEntry>>,
      leafWeights: Map<DecisionTreeLeafId, Double>,
      mutantId: MutantId,
  ): List<Int> =
      (1..REPETITIONS)
          .toList()
          .parallelStream()
          .map { rep ->
            val rng = Random(42L + rep)
            val workingPools = ticksPerLeaf.mapValues { (_, ticks) -> SharedDrawPool(ticks) }
            val roundRobinOrder = workingPools.keys.shuffled(rng).toMutableList()
            var rrPos = 0
            val candidateLeafIds =
                workingPools.keys.filter { (leafWeights[it] ?: 0.0) > 0.0 }.toMutableList()

            fun nextEqualLeafOrNull(): DecisionTreeLeafId? {
              while (roundRobinOrder.isNotEmpty() &&
                  workingPools.getValue(roundRobinOrder[rrPos % roundRobinOrder.size]).isEmpty()) {
                roundRobinOrder.removeAt(rrPos % roundRobinOrder.size)
              }
              if (roundRobinOrder.isEmpty()) return null
              val leafId = roundRobinOrder[rrPos % roundRobinOrder.size]
              rrPos = (rrPos + 1) % roundRobinOrder.size
              return leafId
            }

            fun nextWeightedLeafOrNull(): DecisionTreeLeafId? {
              candidateLeafIds.removeAll { workingPools.getValue(it).isEmpty() }
              return if (candidateLeafIds.isEmpty()) null
              else weightedPickLeaf(candidateLeafIds, leafWeights, rng)
            }

            var useEqualTurn = true
            fun nextLeafId(): DecisionTreeLeafId? {
              val leafId =
                  if (useEqualTurn) nextEqualLeafOrNull() ?: nextWeightedLeafOrNull()
                  else nextWeightedLeafOrNull() ?: nextEqualLeafOrNull()
              useEqualTurn = !useEqualTurn
              return leafId
            }

            var drawn = 0
            var leafId = nextLeafId()
            while (leafId != null) {
              val tick = workingPools.getValue(leafId).drawAndRemoveRandomTick(rng)
              drawn++
              if (tick.mutantId == mutantId && tick.nextTickG0Failed == true) return@map drawn
              leafId = nextLeafId()
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
   * Draws distinct elements without replacement from [source] - the very same shared, read-only
   * list every repetition (and, for the leaf-based strategies, every leaf) reads from - without
   * ever copying it. [source] can hold hundreds of millions of entries, but a pool only ever
   * touches the handful of positions *it itself* has drawn so far: [drawAndRemoveRandomTick]
   * simulates one step of an in-place swap-remove shuffle of [source] (the same swap-remove
   * [evaluateRandomDrawTicks] used to do directly on a private copy), but records the swap in a
   * small per-pool overlay map instead of writing it back into [source] - so [source] itself is
   * never mutated and stays safe to share, completely unchanged, across every concurrently running
   * repetition and pool. A suite of `k` draws (or `k` draws until a kill) therefore costs `k` hash
   * lookups, never a copy of the leaf or pool it draws from.
   */
  internal class SharedDrawPool<T>(private val source: List<T>) {
    private val overlay = HashMap<Int, T>()
    private var remaining = source.size

    private fun at(position: Int): T = overlay[position] ?: source[position]

    /** Whether every element of [source] has been drawn from this pool. */
    fun isEmpty(): Boolean = remaining <= 0

    /**
     * Removes and returns one uniformly random remaining element, via the same swap-remove
     * [evaluateRandomDrawTicks] et al. used to perform in place (overwrite the drawn slot with the
     * last remaining slot, then shrink the remaining range by one) - just replayed against [source]
     * through [overlay] instead of mutating an actual copy.
     */
    fun drawAndRemoveRandomTick(rng: Random): T {
      check(remaining > 0) { "drawAndRemoveRandomTick() called on an exhausted pool" }
      val drawnPos = rng.nextInt(remaining)
      val value = at(drawnPos)
      val lastPos = remaining - 1
      if (drawnPos != lastPos) overlay[drawnPos] = at(lastPos)
      overlay.remove(lastPos)
      remaining--
      return value
    }
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
   * Saves [results] as a single-column CSV under `[basePath]/run_<runId>/size_<suiteSize>/`.
   *
   * @param results One killed-mutant count per repetition.
   * @param identifier Sampling strategy label used in the file name (e.g. `"random_tick"`).
   * @param suiteSize Suite size used for this evaluation run, determines the subfolder.
   * @param runId Decision tree run whose leaf assignments were used, determines the parent folder.
   */
  private fun save(results: List<Int>, identifier: String, suiteSize: Int, runId: Int) {
    val path = basePath(runId).resolve("size_$suiteSize/draw_ticks_${identifier}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(prefix = "Mutants killed ${identifier}\n", separator = "\n") {
          it.toString()
        })
    println("    CSV written to: $path")
  }

  private fun saveTimeToKill(results: List<Int>, strategy: String, mutantId: Int, runId: Int) {
    val path = basePath(runId).resolve("time_to_kill/mutant_${mutantId}/ttk_${strategy}.csv")
    Files.createDirectories(path.parent)
    path.writeText(
        results.joinToString(prefix = "draws_to_kill\n", separator = "\n") { it.toString() })
    println("    CSV written to: $path")
  }
}
