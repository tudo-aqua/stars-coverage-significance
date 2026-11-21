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

import java.time.Instant
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Quantiles of a number of draws.
 *
 * @property p05 5th percentile.
 * @property p25 25th percentile.
 * @property p50 Median.
 * @property p75 75th percentile.
 * @property p95 95th percentile.
 */
@Serializable
data class DrawQuantiles(val p05: Int, val p25: Int, val p50: Int, val p75: Int, val p95: Int)

/**
 * How rarely one mutant is killed, and how many random draws that takes.
 *
 * @property mutantId `mutants.id`.
 * @property killScenarios Valid scenarios that kill it.
 * @property killShare [killScenarios] / pool size.
 * @property expectedDraws Expected random draws until it is killed, (N + 1) / (K + 1).
 * @property draws Quantiles of the draws until it is killed (exact, hypergeometric).
 * @property group Kept mutant of its kill set (smallest id of the mutants with the same kill set).
 * @property groupSize Number of mutants with that kill set.
 * @property representative Whether this mutant is the kept one.
 */
@Serializable
data class MutantRarity(
    val mutantId: Int,
    val killScenarios: Int,
    val killShare: Double,
    val expectedDraws: Double,
    val draws: DrawQuantiles,
    val group: Int,
    val groupSize: Int,
    val representative: Boolean,
)

/**
 * Random drawing after [draws] draws.
 *
 * @property draws Number of scenarios drawn.
 * @property expectedShareKilled Expected share of the killable mutants killed by then.
 * @property pAllKilled Share of simulated random orders that killed all of them by then.
 */
@Serializable
data class CurvePoint(val draws: Int, val expectedShareKilled: Double, val pAllKilled: Double)

/**
 * How many valid scenarios kill exactly [mutantsKilled] mutants.
 *
 * @property mutantsKilled Mutants killed by one scenario (duplicates counted).
 * @property scenarios Valid scenarios that kill that many.
 */
@Serializable data class KillsPerScenario(val mutantsKilled: Int, val scenarios: Int)

/**
 * Kill rarity of one oracle.
 *
 * @property oracle Oracle name.
 * @property pool Valid scenarios.
 * @property removed Scenarios removed because the baseline violates the oracle.
 * @property mutants Mutants (AiPilot excluded).
 * @property killing Mutants killed in at least one valid scenario.
 * @property distinct Distinct kill sets among them.
 * @property rarestKillScenarios Fewest valid scenarios that kill a mutant, or `null`.
 * @property medianExpectedDraws Median over the killing mutants of [MutantRarity.expectedDraws].
 * @property drawsToKillAll Quantiles of the random draws until every killing mutant is killed.
 * @property greedyMinimum Scenarios a greedy set cover needs to kill every killing mutant.
 * @property mutantRows One row per killing mutant, rarest first.
 * @property curve Random drawing progress on a log-spaced grid of draw counts.
 * @property killsPerScenario Distribution of mutants killed per valid scenario.
 */
@Serializable
data class OracleRarity(
    val oracle: String,
    val pool: Int,
    val removed: Int,
    val mutants: Int,
    val killing: Int,
    val distinct: Int,
    val rarestKillScenarios: Int?,
    val medianExpectedDraws: Double?,
    val drawsToKillAll: DrawQuantiles?,
    val greedyMinimum: Int,
    val mutantRows: List<MutantRarity>,
    val curve: List<CurvePoint>,
    val killsPerScenario: List<KillsPerScenario>,
)

/**
 * Contents of `kill_rarity.json`.
 *
 * @property generatedAt When the file was written (UTC).
 * @property thresholds Oracle thresholds used.
 * @property simulatedOrders Random orders simulated for [OracleRarity.drawsToKillAll].
 * @property oracles One entry per oracle.
 */
@Serializable
data class KillRarityReport(
    val generatedAt: String,
    val thresholds: OracleThresholds,
    val simulatedOrders: Int,
    val oracles: List<OracleRarity>,
)

/**
 * How rarely each mutant is killed and how many randomly drawn starting scenarios that takes, per
 * oracle; [KillMatrices.run] writes it as `kill_rarity.json` for its page.
 */
object KillRarity {

  /** Random orders simulated for the draws until all mutants are killed. */
  const val SIMULATED_ORDERS: Int = 1000

  private const val GRID_POINTS = 120

  /**
   * The report written as `kill_rarity.json` by [KillMatrices.run], one entry per matrix.
   *
   * @param matrices The oracles' kill matrices.
   * @param scenarioCount All scenarios, for the number removed.
   * @param mutantCount Mutants (AiPilot excluded).
   * @param thresholds Thresholds the matrices were built with.
   */
  fun report(
      matrices: List<KillMatrix>,
      scenarioCount: Int,
      mutantCount: Int,
      thresholds: OracleThresholds,
  ): KillRarityReport {
    val oracles = matrices.map { compute(it, scenarioCount, mutantCount) }
    for (o in oracles) {
      println(
          "${o.oracle}: ${o.killing} of ${o.mutants} mutants killed, ${o.distinct} distinct; " +
              "all killed after ${o.drawsToKillAll?.p50} random draws (median), " +
              "greedy ${o.greedyMinimum}")
    }
    return KillRarityReport(Instant.now().toString(), thresholds, SIMULATED_ORDERS, oracles)
  }

  /**
   * Kill rarity of one oracle from its cleaned kill matrix.
   *
   * @param matrix The oracle's kill matrix from [KillMatrices.compute].
   * @param scenarioCount All scenarios, for the number removed.
   * @param mutantCount Mutants loaded (AiPilot excluded).
   * @param random Source of the simulated random orders.
   */
  fun compute(
      matrix: KillMatrix,
      scenarioCount: Int,
      mutantCount: Int,
      random: Random = Random(0),
  ): OracleRarity {
    val n = matrix.poolScenarioIds.size
    val reps = matrix.mutantIds.indices.toList()
    val groupOf =
        reps.map { matrix.duplicateGroups[matrix.mutantIds[it]] ?: listOf(matrix.mutantIds[it]) }
    val k = reps.map { matrix.kills[it].cardinality() }

    val rows =
        reps
            .flatMap { r ->
              val q =
                  DrawQuantiles(
                      drawQuantile(n, k[r], 0.05),
                      drawQuantile(n, k[r], 0.25),
                      drawQuantile(n, k[r], 0.5),
                      drawQuantile(n, k[r], 0.75),
                      drawQuantile(n, k[r], 0.95))
              groupOf[r].map { id ->
                MutantRarity(
                    mutantId = id,
                    killScenarios = k[r],
                    killShare = k[r].toDouble() / n,
                    expectedDraws = (n + 1.0) / (k[r] + 1.0),
                    draws = q,
                    group = matrix.mutantIds[r],
                    groupSize = groupOf[r].size,
                    representative = id == matrix.mutantIds[r],
                )
              }
            }
            .sortedWith(compareBy({ it.killScenarios }, { it.mutantId }))

    val grid = logGrid(n)
    val weights = groupOf.map { it.size.toDouble() }
    val totalWeight = weights.sum()
    val expected = DoubleArray(grid.size)
    for (r in reps) {
      val killed = killedByDraws(n, k[r], grid)
      for (i in grid.indices) expected[i] += weights[r] * killed[i] / totalWeight
    }
    val allKilled = simulateDrawsToKillAll(matrix, random)
    val curve =
        grid.mapIndexed { i, d ->
          CurvePoint(
              d,
              if (reps.isEmpty()) 0.0 else expected[i],
              if (allKilled.isEmpty()) 0.0
              else allKilled.count { it <= d }.toDouble() / allKilled.size)
        }

    val perScenario = IntArray(n)
    for (r in reps) {
      val b = matrix.kills[r]
      var p = b.nextSetBit(0)
      while (p >= 0) {
        perScenario[p] += groupOf[r].size
        p = b.nextSetBit(p + 1)
      }
    }
    val histogram =
        perScenario
            .groupBy { it }
            .map { (killed, list) -> KillsPerScenario(killed, list.size) }
            .sortedBy { it.mutantsKilled }

    return OracleRarity(
        oracle = matrix.oracle.name,
        pool = n,
        removed = scenarioCount - n,
        mutants = mutantCount,
        killing = rows.size,
        distinct = reps.size,
        rarestKillScenarios = k.minOrNull(),
        medianExpectedDraws =
            rows.map { it.expectedDraws }.sorted().let { if (it.isEmpty()) null else median(it) },
        drawsToKillAll = if (allKilled.isEmpty()) null else quantiles(allKilled),
        greedyMinimum = greedyCover(matrix),
        mutantRows = rows,
        curve = curve,
        killsPerScenario = histogram,
    )
  }

  /**
   * Smallest number of draws `d` (without replacement, from [n] scenarios of which [k] kill) with
   * P(killed within `d` draws) >= [q].
   */
  fun drawQuantile(n: Int, k: Int, q: Double): Int {
    require(k in 1..n) { "k must be in 1..n" }
    var survive = 1.0 // P(no killing scenario among the first d draws)
    var d = 0
    while (1.0 - survive < q - 1e-12) {
      survive *= (n - k - d).toDouble() / (n - d)
      d++
    }
    return d
  }

  /** P(killed within d draws) for every `d` of [grid] (ascending). */
  fun killedByDraws(n: Int, k: Int, grid: IntArray): DoubleArray {
    val out = DoubleArray(grid.size)
    var survive = 1.0
    var d = 0
    for ((i, target) in grid.withIndex()) {
      while (d < target && survive > 0.0) {
        survive *= maxOf(0, n - k - d).toDouble() / (n - d)
        d++
      }
      out[i] = 1.0 - survive
    }
    return out
  }

  /**
   * Scenarios a greedy set cover needs so that every kept mutant is killed at least once: take the
   * scenario that kills the most mutants not yet killed, until none is left. An upper bound on the
   * true minimum.
   */
  fun greedyCover(matrix: KillMatrix): Int {
    val alive = matrix.kills.indices.toMutableSet()
    var chosen = 0
    val counts = IntArray(matrix.poolScenarioIds.size)
    while (alive.isNotEmpty()) {
      counts.fill(0)
      for (r in alive) {
        val b = matrix.kills[r]
        var p = b.nextSetBit(0)
        while (p >= 0) {
          counts[p]++
          p = b.nextSetBit(p + 1)
        }
      }
      val best = counts.indices.maxBy { counts[it] }
      alive.removeAll { matrix.kills[it][best] }
      chosen++
    }
    return chosen
  }

  /** Draws until every kept mutant is killed, for [SIMULATED_ORDERS] random orders of the pool. */
  private fun simulateDrawsToKillAll(matrix: KillMatrix, random: Random): IntArray {
    if (matrix.kills.isEmpty()) return IntArray(0)
    val n = matrix.poolScenarioIds.size
    val sets = matrix.kills.map { it.stream().toArray() }
    val rank = IntArray(n) { it }
    return IntArray(SIMULATED_ORDERS) {
      for (i in n - 1 downTo 1) { // Fisher-Yates: rank[p] = position of scenario p in the order
        val j = random.nextInt(i + 1)
        val t = rank[i]
        rank[i] = rank[j]
        rank[j] = t
      }
      var last = 0
      for (s in sets) {
        var first = Int.MAX_VALUE
        for (p in s) if (rank[p] < first) first = rank[p]
        if (first > last) last = first
      }
      last + 1
    }
  }

  private fun quantiles(values: IntArray): DrawQuantiles {
    val s = values.sorted()
    fun at(q: Double) = s[(ceil(q * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
    return DrawQuantiles(at(0.05), at(0.25), at(0.5), at(0.75), at(0.95))
  }

  private fun median(sorted: List<Double>): Double =
      if (sorted.size % 2 == 1) sorted[sorted.size / 2]
      else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

  /** About [GRID_POINTS] distinct draw counts from 1 to [n], evenly spaced on a log scale. */
  private fun logGrid(n: Int): IntArray =
      (0..GRID_POINTS)
          .map { (n.toDouble().pow(it.toDouble() / GRID_POINTS)).toInt().coerceIn(1, n) }
          .distinct()
          .toIntArray()
}
