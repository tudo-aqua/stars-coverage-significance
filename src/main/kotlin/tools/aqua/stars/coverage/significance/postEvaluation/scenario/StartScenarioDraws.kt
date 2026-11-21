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

package tools.aqua.stars.coverage.significance.postEvaluation.scenario

import java.io.File
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.NEXT_TICK_SUITE_SIZES
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.tables.KillMatricesTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupingMutantSplitsTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupingRunsTable
import tools.aqua.stars.coverage.significance.tsc.tsc
import tools.aqua.stars.coverage.significance.tsc.tscWithStaticDistances
import tools.aqua.stars.coverage.significance.utils.getJsonString

/**
 * A grouping of an oracle's pool.
 *
 * @property runId Its [StartScenarioGroupingRunsTable] id.
 * @property name Readable name, e.g. `tsc_static` or `tree_with_ego_lane_L68`.
 * @property groupOf Dense group index (0 until [groupCount]) per pool position.
 * @property groupCount Number of groups.
 */
@Suppress("UseDataClass") // A data class would compare the arrays by reference in equals().
class PoolGrouping(val runId: Int, val name: String, val groupOf: IntArray, val groupCount: Int)

/**
 * Random-strategy check of one oracle: mean time to kill against (N + 1) / (K + 1).
 *
 * @property maxAbsZ Largest |z| over the scoring mutants, with the exact variance of the first hit.
 * @property mutantId The mutant with that |z|.
 */
@Serializable data class RandomCheck(val maxAbsZ: Double, val mutantId: Int)

/**
 * `summary.json` of one oracle.
 *
 * @property oracle Oracle name.
 * @property killMatrixId The kill matrix the trees were trained on.
 * @property pool Valid scenarios.
 * @property trainingMutantIds Mutants the greedy order and the class weights use.
 * @property scoringMutantIds Mutants the suites are scored on.
 * @property groupings Grouping name -> [StartScenarioGroupingRunsTable] id.
 * @property groupCounts Grouping name -> number of groups in the pool.
 * @property repetitions Repetitions per random strategy.
 * @property randomCheck Acceptance check of the random strategy.
 * @property seconds Wall-clock time per stage.
 */
@Serializable
data class StartScenarioDrawsSummary(
    val oracle: String,
    val killMatrixId: Int,
    val pool: Int,
    val trainingMutantIds: List<Int>,
    val scoringMutantIds: List<Int>,
    val groupings: Map<String, Int>,
    val groupCounts: Map<String, Int>,
    val repetitions: Int,
    val randomCheck: RandomCheck,
    val seconds: Map<String, Double>,
)

/**
 * Steps D and E of `docs/test-bed-plan.md`: draw orders over each oracle's pool by the random,
 * greedy and grouped strategies, using every TSC and tree grouping, and the time to kill of each
 * scoring mutant. Every order covers the whole pool (D10). Only lookups, no simulation.
 */
object StartScenarioDraws {

  /** Outputs go to `<OUTPUT_DIR>/<condition>/<oracle>/`. */
  const val OUTPUT_DIR: String = "postEvaluation/start_scenario_draws"

  /** Strategy names in the CSV files. */
  private const val RANDOM = "random"
  private const val GREEDY = "greedy"
  private const val GROUPED_EQUAL = "grouped_equal"
  private const val GROUPED_WEIGHTED = "grouped_weighted"
  private const val NO_GROUPING = "none"

  /** |z| above this in the random check is reported as a warning. */
  private const val MAX_RANDOM_Z = 4.0

  private val json = Json { prettyPrint = true }

  /**
   * Runs the draws of every oracle that has tree groupings of [condition] and writes
   * `time_to_kill.csv`, `suite_size_curves.csv` and `summary.json` per oracle.
   *
   * @param condition Condition of the tree runs, e.g. `full`.
   * @param seed Repetition `r` uses `Random(seed + r)`.
   */
  fun run(condition: String = "full", seed: Long = 0) {
    val tscs = latestTscGroupings()
    val trees = latestTreeRuns(condition)
    check(trees.isNotEmpty()) { "No tree runs of condition '$condition'" }
    for ((oracle, runs) in trees) {
      val t0 = System.nanoTime()
      val killMatrixId = runs.first().killMatrixId
      val m = KillMatrices.read(killMatrixId)
      val (training, scoring) = mutantSplit(runs.map { it.id })
      val groupings =
          (tscs + runs.map { it.id to "tree_${it.featureSet}_L${it.leafBound}" }).map { (id, name)
            ->
            poolGrouping(id, name, m.poolScenarioIds)
          }
      val t1 = System.nanoTime()
      val column = m.mutantIds.withIndex().associate { (i, id) -> id to i }
      val trainingKills = training.map { m.kills[column.getValue(it)] }
      val scoringKills = scoring.map { m.kills[column.getValue(it)] }
      val results = draws(m.poolScenarioIds.size, trainingKills, scoringKills, groupings, seed)
      val t2 = System.nanoTime()
      val dir = File(OUTPUT_DIR, "$condition/${oracle.name.lowercase()}").apply { mkdirs() }
      write(dir, results, scoring)
      val check = randomCheck(results, scoringKills, scoring, m.poolScenarioIds.size)
      if (check.maxAbsZ > MAX_RANDOM_Z) {
        println(
            "WARNING: random draws of mutant ${check.mutantId} deviate from (N+1)/(K+1), z = ${check.maxAbsZ}")
      }
      val summary =
          StartScenarioDrawsSummary(
              oracle = oracle.name,
              killMatrixId = killMatrixId,
              pool = m.poolScenarioIds.size,
              trainingMutantIds = training,
              scoringMutantIds = scoring,
              groupings = groupings.associate { it.name to it.runId },
              groupCounts = groupings.associate { it.name to it.groupCount },
              repetitions = REPETITIONS,
              randomCheck = check,
              seconds = mapOf("load" to (t1 - t0) / 1e9, "draws" to (t2 - t1) / 1e9),
          )
      val text = json.encodeToString(StartScenarioDrawsSummary.serializer(), summary)
      File(dir, "summary.json").writeText(text)
      println(text)
      println("Wrote $dir")
    }
  }

  /** Time to kill per scoring mutant, for one strategy, grouping and repetition. */
  @Suppress("UseDataClass") // A data class would compare the array by reference in equals().
  private class Result(
      val strategy: String,
      val grouping: String,
      val repetition: Int,
      val timeToKill: IntArray,
  )

  private fun draws(
      n: Int,
      trainingKills: List<BitSet>,
      scoringKills: List<BitSet>,
      groupings: List<PoolGrouping>,
      seed: Long,
  ): List<Result> {
    fun repeated(strategy: String, grouping: String, order: (Random) -> IntArray) =
        (0 until REPETITIONS)
            .toList()
            .parallelStream()
            .map { rep ->
              Result(strategy, grouping, rep, timeToKill(order(Random(seed + rep)), scoringKills))
            }
            .toList()

    val out = mutableListOf<Result>()
    out += repeated(RANDOM, NO_GROUPING) { randomOrder(n, it) }
    out += Result(GREEDY, NO_GROUPING, 0, timeToKill(greedyOrder(trainingKills, n), scoringKills))
    for (g in groupings) {
      val weights = groupWeights(g.groupOf, g.groupCount, trainingKills)
      out += repeated(GROUPED_EQUAL, g.name) { groupedEqualOrder(g.groupOf, g.groupCount, it) }
      out += repeated(GROUPED_WEIGHTED, g.name) { groupedWeightedOrder(g.groupOf, weights, it) }
    }
    return out
  }

  private fun write(dir: File, results: List<Result>, scoring: List<Int>) {
    File(dir, "time_to_kill.csv").printWriter().use { w ->
      w.println("strategy,grouping,repetition,mutant_id,time_to_kill")
      for (r in results) {
        r.timeToKill.forEachIndexed { i, t ->
          w.println("${r.strategy},${r.grouping},${r.repetition},${scoring[i]},$t")
        }
      }
    }
    File(dir, "suite_size_curves.csv").printWriter().use { w ->
      w.println("strategy,grouping,repetition,suite_size,mutants_killed")
      for (r in results) {
        for (size in NEXT_TICK_SUITE_SIZES) {
          val killed = r.timeToKill.count { it in 1..size }
          w.println("${r.strategy},${r.grouping},${r.repetition},$size,$killed")
        }
      }
    }
  }

  /**
   * Plan acceptance for the random strategy: the mean time to kill of each mutant matches (N + 1) /
   * (K + 1); z uses the exact variance of the first hit when drawing without replacement.
   */
  private fun randomCheck(
      results: List<Result>,
      scoringKills: List<BitSet>,
      scoring: List<Int>,
      n: Int,
  ): RandomCheck {
    val random = results.filter { it.strategy == RANDOM }
    return scoring.indices
        .map { i ->
          val k = scoringKills[i].cardinality().toDouble()
          val expected = (n + 1) / (k + 1)
          val variance = (n + 1) * (n - k) * k / ((k + 1) * (k + 1) * (k + 2))
          val mean = random.map { it.timeToKill[i] }.average()
          val z = if (variance == 0.0) 0.0 else (mean - expected) / sqrt(variance / random.size)
          RandomCheck(abs(z), scoring[i])
        }
        .maxBy { it.maxAbsZ }
  }

  // ---------------------------------------------------------------- orders (pool positions)

  /** A uniform permutation of `0 until n`. */
  fun randomOrder(n: Int, random: Random): IntArray = IntArray(n) { it }.apply { shuffle(random) }

  /**
   * Deterministic greedy order: repeatedly the position killing the most training mutants not yet
   * killed, ties by position (the pool is sorted by scenario id). Resets when all are killed, or
   * when no remaining position adds a kill.
   */
  fun greedyOrder(trainingKills: List<BitSet>, n: Int): IntArray {
    // shortcut: training mutants packed in a Long, at most 64; switch to LongArray masks when a
    // condition trains on more.
    require(trainingKills.size <= Long.SIZE_BITS) { "Greedy order supports at most 64 mutants" }
    val masks = LongArray(n)
    trainingKills.forEachIndexed { j, kills ->
      kills.stream().forEach { masks[it] = masks[it] or (1L shl j) }
    }
    // Positions with the same mask are interchangeable but for the tie rule, so queue them by mask.
    val queues = (0 until n).groupBy { masks[it] }.entries.map { it.key to ArrayDeque(it.value) }
    val all = if (trainingKills.size == Long.SIZE_BITS) -1L else (1L shl trainingKills.size) - 1
    var open = all
    val order = IntArray(n)
    var i = 0
    while (i < n) {
      var best: ArrayDeque<Int>? = null
      var bestMask = 0L
      var bestCount = -1
      for ((mask, queue) in queues) {
        if (queue.isEmpty()) continue
        val count = java.lang.Long.bitCount(mask and open)
        if (count > bestCount || (count == bestCount && queue.first() < best!!.first())) {
          best = queue
          bestMask = mask
          bestCount = count
        }
      }
      if (bestCount == 0 && open != all) {
        open = all
        continue
      }
      order[i++] = checkNotNull(best).removeFirst()
      open = open and bestMask.inv()
      if (open == 0L) open = all
    }
    return order
  }

  /** Rounds over the non-empty groups in a random order, one uniform position from each. */
  fun groupedEqualOrder(groupOf: IntArray, groupCount: Int, random: Random): IntArray {
    val members = shuffledMembers(groupOf, groupCount, random)
    val order = IntArray(groupOf.size)
    var i = 0
    var active = members.indices.filter { members[it].isNotEmpty() }
    while (active.isNotEmpty()) {
      for (g in active.shuffled(random)) order[i++] = members[g].removeLast()
      active = active.filter { members[it].isNotEmpty() }
    }
    return order
  }

  /**
   * Picks a group with probability proportional to [weights] among the non-empty groups, then a
   * uniform position from it. Once every group with positive weight is empty, picks the remaining
   * groups uniformly, so every position is drawn (D10).
   */
  fun groupedWeightedOrder(groupOf: IntArray, weights: DoubleArray, random: Random): IntArray {
    val members = shuffledMembers(groupOf, weights.size, random)
    val order = IntArray(groupOf.size)
    var i = 0
    val weighted =
        members.indices.filter { weights[it] > 0 && members[it].isNotEmpty() }.toMutableList()
    while (weighted.isNotEmpty()) {
      val cumulative = DoubleArray(weighted.size)
      var total = 0.0
      weighted.forEachIndexed { j, g ->
        total += weights[g]
        cumulative[j] = total
      }
      // Draw until a group empties; then the cumulative weights are rebuilt.
      while (true) {
        val r = random.nextDouble() * total
        val j =
            cumulative
                .binarySearch(r)
                .let { if (it >= 0) it + 1 else -it - 1 }
                .coerceAtMost(weighted.lastIndex)
        val g = weighted[j]
        order[i++] = members[g].removeLast()
        if (members[g].isEmpty()) {
          weighted.removeAt(j)
          break
        }
      }
    }
    val rest = members.indices.filter { members[it].isNotEmpty() }.toMutableList()
    while (rest.isNotEmpty()) {
      val j = random.nextInt(rest.size)
      order[i++] = members[rest[j]].removeLast()
      if (members[rest[j]].isEmpty()) rest.removeAt(j)
    }
    return order
  }

  /** Kill rate of each group on the training mutants: killed pairs / (positions x mutants). */
  fun groupWeights(groupOf: IntArray, groupCount: Int, trainingKills: List<BitSet>): DoubleArray {
    val size = IntArray(groupCount)
    groupOf.forEach { size[it]++ }
    val killed = LongArray(groupCount)
    trainingKills.forEach { kills -> kills.stream().forEach { killed[groupOf[it]]++ } }
    return DoubleArray(groupCount) {
      if (size[it] == 0 || trainingKills.isEmpty()) 0.0
      else killed[it].toDouble() / (size[it].toDouble() * trainingKills.size)
    }
  }

  /** 1 + the first position in [order] of a scenario killing each mutant, or -1 if none does. */
  fun timeToKill(order: IntArray, kills: List<BitSet>): IntArray {
    val drawnAt = IntArray(order.size)
    order.forEachIndexed { i, p -> drawnAt[p] = i }
    return IntArray(kills.size) { j ->
      var first = Int.MAX_VALUE
      kills[j].stream().forEach { if (drawnAt[it] < first) first = drawnAt[it] }
      if (first == Int.MAX_VALUE) -1 else first + 1
    }
  }

  private fun shuffledMembers(groupOf: IntArray, groupCount: Int, random: Random) =
      List(groupCount) { ArrayList<Int>() }
          .also { lists -> groupOf.forEachIndexed { p, g -> lists[g].add(p) } }
          .onEach { it.shuffle(random) }

  // ---------------------------------------------------------------- loading

  /** A tree run, with the oracle of its kill matrix. */
  private data class TreeRun(
      val id: Int,
      val killMatrixId: Int,
      val featureSet: String,
      val leafBound: Int,
  )

  /** Per oracle the latest tree run of each (feature set, leaf bound), all on one kill matrix. */
  private fun latestTreeRuns(condition: String): Map<KillOracle, List<TreeRun>> = transaction {
    val r = StartScenarioGroupingRunsTable
    val runs =
        r.selectAll()
            .where {
              (r.kind eq StartScenarioTscGroupings.KIND_TREE) and (r.condition eq condition)
            }
            .map {
              TreeRun(
                  it[r.id].value,
                  checkNotNull(it[r.killMatrixId]).value,
                  checkNotNull(it[r.featureSet]),
                  checkNotNull(it[r.leafBound]))
            }
    val oracleOf =
        KillMatricesTable.selectAll().associate {
          it[KillMatricesTable.id].value to KillOracle.valueOf(it[KillMatricesTable.oracle])
        }
    runs
        .groupBy { oracleOf.getValue(it.killMatrixId) }
        .mapValues { (_, list) ->
          // The newest tree decides the kill matrix; older matrices' trees are left out.
          val matrix = list.maxBy { it.id }.killMatrixId
          list
              .filter { it.killMatrixId == matrix }
              .groupBy { it.featureSet to it.leafBound }
              .values
              .map { same -> same.maxBy { it.id } }
              .sortedWith(compareBy({ it.featureSet }, { it.leafBound }))
        }
        .toSortedMap()
  }

  /** The latest grouping run per TSC, as (run id, name). */
  private fun latestTscGroupings(): List<Pair<Int, String>> {
    val projectId = TSCsRepository.getByJson(tsc().getJsonString())?.id
    val staticId = TSCsRepository.getByJson(tscWithStaticDistances().getJsonString())?.id
    return transaction {
      val r = StartScenarioGroupingRunsTable
      r.selectAll()
          .where { r.kind eq StartScenarioTscGroupings.KIND_TSC }
          .map { it[r.id].value to checkNotNull(it[r.tscId]).value }
          .groupBy { it.second }
          .map { (tscId, list) ->
            val name =
                when (tscId) {
                  projectId -> "tsc_project"
                  staticId -> "tsc_static"
                  else -> "tsc_$tscId"
                }
            list.maxOf { it.first } to name
          }
          .sortedBy { it.second }
    }
  }

  /** Training and scoring mutants; every tree of an oracle must have the same split. */
  private fun mutantSplit(runIds: List<Int>): Pair<List<Int>, List<Int>> = transaction {
    val s = StartScenarioGroupingMutantSplitsTable
    val splits =
        runIds.map { id ->
          s.selectAll()
              .where { s.runId eq id }
              .map { Triple(it[s.mutantId].value, it[s.trainedOn], it[s.scoredOn]) }
              .toSet()
        }
    check(splits.distinct().size == 1) { "Tree runs $runIds use different mutant splits" }
    val split = splits.first()
    split.filter { it.second }.map { it.first }.sorted() to
        split.filter { it.third }.map { it.first }.sorted()
  }

  /** The groups of [runId] over [pool], renumbered densely. */
  private fun poolGrouping(runId: Int, name: String, pool: IntArray): PoolGrouping = transaction {
    val a = StartScenarioGroupAssignmentsTable
    val groupOfScenario =
        a.select(a.scenarioConfigId, a.groupId)
            .where { a.runId eq runId }
            .associate { it[a.scenarioConfigId].value to it[a.groupId] }
    val dense = HashMap<Int, Int>()
    val groupOf =
        IntArray(pool.size) { p ->
          val g =
              groupOfScenario[pool[p]]
                  ?: error("Grouping $name (run $runId) has no group for scenario ${pool[p]}")
          dense.getOrPut(g) { dense.size }
        }
    PoolGrouping(runId, name, groupOf, dense.size)
  }
}
