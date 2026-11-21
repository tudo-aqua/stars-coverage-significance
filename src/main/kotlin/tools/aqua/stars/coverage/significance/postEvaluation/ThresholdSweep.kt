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

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pool and kill numbers of one oracle setting.
 *
 * @property baselineViolations Scenarios the baseline violates; removed from the pool (D9).
 * @property baselineViolationsWithLaneChange Of those, scenarios in which the baseline changed
 *   lanes.
 * @property poolLoss Share of scenarios removed.
 * @property pool Scenarios left in the pool.
 * @property killableMutants Mutants killed in at least one pool scenario.
 * @property mutantsKilledInOver90Pct Mutants killed in more than 90% of the pool.
 * @property killPairs Killing (scenario, mutant) pairs.
 * @property jaccardWithG0 Jaccard overlap of the kill pairs with the G0 kill pairs.
 */
data class SweepStats(
    val baselineViolations: Int,
    val baselineViolationsWithLaneChange: Int,
    val poolLoss: Double,
    val pool: Int,
    val killableMutants: Int,
    val mutantsKilledInOver90Pct: Int,
    val killPairs: Long,
    val jaccardWithG0: Double,
)

/**
 * One line of `sweep.csv`.
 *
 * @property oracle `g0`, `ttc` or `tg`.
 * @property side `front`, `rear`, `front+rear` or empty.
 * @property mode Rear mode, or empty.
 * @property threshold Threshold in seconds (`front/rear` for combined rows), or empty.
 * @property stats The numbers.
 */
data class SweepRow(
    val oracle: String,
    val side: String,
    val mode: String,
    val threshold: String,
    val stats: SweepStats,
)

/**
 * Chosen front and rear thresholds of one oracle and rear mode, combined with OR.
 *
 * @property oracle `ttc` or `tg`.
 * @property rearMode `always` or `after_lane_change`.
 * @property front Front threshold (s).
 * @property rear Rear threshold (s).
 * @property combinedPoolLoss Share of scenarios removed by both together.
 * @property withinLimit Whether [combinedPoolLoss] stays within the limit.
 */
@Serializable
data class CombinedChoice(
    val oracle: String,
    val rearMode: String,
    val front: Float,
    val rear: Float,
    val combinedPoolLoss: Double,
    val withinLimit: Boolean,
)

/**
 * Contents of `chosen.json`.
 *
 * @property maxPoolLoss The pool-loss limit used.
 * @property grid Thresholds tried per oracle.
 * @property perSide Chosen threshold per `oracle/side/mode`, or `null` if none stays within the
 *   limit.
 * @property combined Front and rear choices combined.
 * @property seconds Wall-clock time per stage.
 */
@Serializable
data class ChosenThresholds(
    val maxPoolLoss: Double,
    val grid: Map<String, List<Float>>,
    val perSide: Map<String, Float?>,
    val combined: List<CombinedChoice>,
    val seconds: Map<String, Double>,
)

/**
 * Step A.2a of `docs/test-bed-plan.md`: threshold sweep for the TTC and TG oracles (P5, P6).
 *
 * For every [OracleMeasure] and every threshold of [GRID], counts the scenarios the baseline
 * violates (`min < threshold`). Per measure, the largest threshold whose share of such scenarios is
 * at most `maxPoolLoss` is chosen; only the baseline enters this choice. The chosen front and rear
 * thresholds are then combined (front OR rear) and their joint pool loss is reported, which can
 * exceed the limit. Mutant numbers are descriptive only. AiPilot is left out of them.
 */
object ThresholdSweep {

  /** Thresholds tried per oracle (s). */
  val GRID: Map<String, List<Float>> =
      mapOf(
          TTC to listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f),
          TG to listOf(0.5f, 0.7f, 0.9f, 1.0f, 1.2f, 1.5f),
      )

  /** Default pool-loss limit (P5). */
  const val DEFAULT_MAX_POOL_LOSS: Double = 0.05

  /** Where `sweep.csv` and `chosen.json` are written. */
  const val OUTPUT_DIR: String = "postEvaluation/threshold_sweep"

  private const val OVER_KILL_SHARE = 0.9
  private val json = Json { prettyPrint = true }

  /**
   * Loads the table, runs the sweep, prints it and writes the outputs to [OUTPUT_DIR].
   *
   * @param maxPoolLoss Largest share of scenarios the baseline may violate.
   */
  fun run(maxPoolLoss: Double = DEFAULT_MAX_POOL_LOSS) {
    val t0 = System.nanoTime()
    val data = OutcomeMatrices.load()
    val t1 = System.nanoTime()
    println("${data.scenarioCount} scenarios, ${data.mutantCount} mutants (AiPilot excluded)")

    val (rows, chosen) = sweep(data, maxPoolLoss)
    val t2 = System.nanoTime()

    val dir = File(OUTPUT_DIR).apply { mkdirs() }
    val csv = toCsv(rows)
    File(dir, "sweep.csv").writeText(csv)
    val seconds = mapOf("load" to (t1 - t0) / 1e9, "sweep" to (t2 - t1) / 1e9)
    val chosenJson =
        json.encodeToString(ChosenThresholds.serializer(), chosen.copy(seconds = seconds))
    File(dir, "chosen.json").writeText(chosenJson)
    print(csv)
    println(chosenJson)
    println("Wrote $dir/sweep.csv and chosen.json")
  }

  /**
   * Runs the sweep on [data].
   *
   * @return The `sweep.csv` rows and the chosen thresholds (without timings).
   */
  fun sweep(data: OutcomeMatrices, maxPoolLoss: Double): Pair<List<SweepRow>, ChosenThresholds> {
    val rows = mutableListOf<SweepRow>()
    val g0 = stats(data, data.baselineAccident, data.g0Kills)
    rows += SweepRow("g0", "", "", "", g0)

    val chosen = mutableMapOf<OracleMeasure, Float?>()
    for (measure in OracleMeasure.entries) {
      val grid = GRID.getValue(measure.oracle)
      var best: Float? = null
      for (threshold in grid) {
        val s = evaluate(data, listOf(measure to threshold))
        rows += SweepRow(measure.oracle, measure.side, measure.mode, threshold.toString(), s)
        if (s.poolLoss <= maxPoolLoss) best = threshold
      }
      if (best == grid.last()) {
        println(
            "WARNING: ${measure.oracle}/${measure.side}/${measure.mode} is at the top of the " +
                "grid; the pool-loss limit does not bound it")
      }
      chosen[measure] = best
    }

    val combined = mutableListOf<CombinedChoice>()
    for ((front, rears) in
        listOf(
            OracleMeasure.TTC_FRONT to
                listOf(OracleMeasure.TTC_REAR, OracleMeasure.TTC_REAR_AFTER_LC),
            OracleMeasure.TG_FRONT to listOf(OracleMeasure.TG_REAR, OracleMeasure.TG_REAR_AFTER_LC),
        )) {
      for (rear in rears) {
        val f = chosen[front]
        val r = chosen[rear]
        if (f == null || r == null) {
          println(
              "WARNING: no ${front.oracle} threshold within the limit (front $f, rear/${rear.mode} $r)")
          continue
        }
        val s = evaluate(data, listOf(front to f, rear to r))
        rows += SweepRow(front.oracle, "front+rear", rear.mode, "$f/$r", s)
        combined +=
            CombinedChoice(front.oracle, rear.mode, f, r, s.poolLoss, s.poolLoss <= maxPoolLoss)
      }
    }

    val perSide = chosen.entries.associate { (m, t) -> "${m.oracle}/${m.side}/${m.mode}" to t }
    return rows to ChosenThresholds(maxPoolLoss, GRID, perSide, combined, emptyMap())
  }

  /**
   * Evaluates the oracle "any of [thresholds] violated" (`min < threshold`, combined with OR). A
   * kill is a mutant violation in a scenario the baseline does not violate (D9).
   */
  fun evaluate(data: OutcomeMatrices, thresholds: List<Pair<OracleMeasure, Float>>): SweepStats {
    val n = data.scenarioCount
    val baseCols = thresholds.map { (m, t) -> data.baseline.getValue(m) to t }
    val baseViolations = BooleanArray(n) { s -> baseCols.any { (col, t) -> col[s] < t } }
    val mutCols = thresholds.map { (m, t) -> data.mutant.getValue(m) to t }
    val kills =
        BooleanArray(data.mutantCount * n) { i ->
          !baseViolations[i % n] && mutCols.any { (col, t) -> col[i] < t }
        }
    return stats(data, baseViolations, kills)
  }

  private fun stats(
      data: OutcomeMatrices,
      baseViolations: BooleanArray,
      kills: BooleanArray
  ): SweepStats {
    val n = data.scenarioCount
    val violations = baseViolations.count { it }
    val withLaneChange = (0 until n).count { baseViolations[it] && data.baselineLaneChanged[it] }
    val pool = n - violations
    var killPairs = 0L
    var intersection = 0L
    var union = 0L
    var killable = 0
    var over90 = 0
    for (m in 0 until data.mutantCount) {
      var k = 0
      for (i in m * n until (m + 1) * n) {
        if (kills[i]) k++
        if (kills[i] && data.g0Kills[i]) intersection++
        if (kills[i] || data.g0Kills[i]) union++
      }
      killPairs += k
      if (k > 0) killable++
      if (k > OVER_KILL_SHARE * pool) over90++
    }
    return SweepStats(
        baselineViolations = violations,
        baselineViolationsWithLaneChange = withLaneChange,
        poolLoss = violations.toDouble() / n,
        pool = pool,
        killableMutants = killable,
        mutantsKilledInOver90Pct = over90,
        killPairs = killPairs,
        jaccardWithG0 = if (union > 0) intersection.toDouble() / union else Double.NaN,
    )
  }

  private fun toCsv(rows: List<SweepRow>): String = buildString {
    appendLine(
        "oracle,side,mode,threshold,baseline_violations,baseline_violations_with_lane_change," +
            "pool_loss,pool,killable_mutants,mutants_killed_in_over_90pct,kill_pairs,jaccard_with_g0")
    for (r in rows) {
      val s = r.stats
      appendLine(
          listOf(
                  r.oracle,
                  r.side,
                  r.mode,
                  r.threshold,
                  s.baselineViolations,
                  s.baselineViolationsWithLaneChange,
                  s.poolLoss,
                  s.pool,
                  s.killableMutants,
                  s.mutantsKilledInOver90Pct,
                  s.killPairs,
                  s.jaccardWithG0)
              .joinToString(","))
    }
  }
}
