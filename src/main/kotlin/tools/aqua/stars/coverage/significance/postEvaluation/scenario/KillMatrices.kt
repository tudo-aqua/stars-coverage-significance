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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tools.aqua.stars.coverage.significance.utils.CliArgs

/** The three oracles of D1. */
enum class KillOracle {
  /** Accident (monitor G0). */
  G0,
  /** TTC below threshold, front or rear. */
  TTC,
  /** Time gap below threshold, front or rear. */
  TG,
}

/** When the vehicle behind counts for the TTC and TG oracles (D14). */
enum class RearMode {
  /** At every tick. */
  ALWAYS,
  /** Only within the lane-change window after an ego lane change. */
  AFTER_LANE_CHANGE,
}

/**
 * Oracle thresholds in seconds. The defaults are D12 to D14 of `docs/test-bed-plan.md`.
 *
 * @property ttcFront TTC threshold for the vehicle in front.
 * @property ttcRear TTC threshold for the vehicle behind.
 * @property tgFront Time-gap threshold for the vehicle in front.
 * @property tgRear Time-gap threshold for the vehicle behind.
 * @property rearMode When the vehicle behind counts.
 */
@Serializable
data class OracleThresholds(
    val ttcFront: Float = 1.5f,
    val ttcRear: Float = 1.5f,
    val tgFront: Float = 0.8f,
    val tgRear: Float = 0.5f,
    val rearMode: RearMode = RearMode.AFTER_LANE_CHANGE,
) {
  /** The (measure, threshold) pairs that [oracle] combines with OR; empty for [KillOracle.G0]. */
  fun conditions(oracle: KillOracle): List<Pair<OracleMeasure, Float>> {
    val afterLc = rearMode == RearMode.AFTER_LANE_CHANGE
    return when (oracle) {
      KillOracle.G0 -> emptyList()
      KillOracle.TTC ->
          listOf(
              OracleMeasure.TTC_FRONT to ttcFront,
              (if (afterLc) OracleMeasure.TTC_REAR_AFTER_LC else OracleMeasure.TTC_REAR) to ttcRear)
      KillOracle.TG ->
          listOf(
              OracleMeasure.TG_FRONT to tgFront,
              (if (afterLc) OracleMeasure.TG_REAR_AFTER_LC else OracleMeasure.TG_REAR) to tgRear)
    }
  }

  /** Parsing from the command line. */
  companion object {
    /**
     * Reads `--ttcFront`, `--ttcRear`, `--tgFront`, `--tgRear` (seconds) and
     * `--rearMode=always|after_lane_change`; anything not given keeps its default.
     */
    fun fromArgs(args: Array<String>): OracleThresholds {
      val d = OracleThresholds()
      fun seconds(name: String) = CliArgs.optionalDouble(args, name)?.toFloat()
      val rearMode =
          args
              .firstOrNull { it.startsWith("--rearMode=") }
              ?.substringAfter("=")
              ?.let { RearMode.valueOf(it.uppercase()) } ?: d.rearMode
      return OracleThresholds(
          ttcFront = seconds("ttcFront") ?: d.ttcFront,
          ttcRear = seconds("ttcRear") ?: d.ttcRear,
          tgFront = seconds("tgFront") ?: d.tgFront,
          tgRear = seconds("tgRear") ?: d.tgRear,
          rearMode = rearMode,
      )
    }
  }
}

/**
 * A scenario the baseline violates; it is removed from the oracle's pool (step A.2).
 *
 * @property scenarioConfigId The scenario.
 * @property anyMutantAvoided Whether at least one mutant did not violate the oracle there.
 */
@Serializable
data class BaselineViolation(val scenarioConfigId: Int, val anyMutantAvoided: Boolean)

/**
 * The cleaned kill matrix of one oracle (steps A.2 and A.3 of `docs/test-bed-plan.md`).
 *
 * A kill is a mutant violating the oracle in a scenario the baseline does not violate (D9).
 * Scenarios the baseline violates are not in the pool. Mutants that kill nowhere are dropped, and
 * of mutants with identical kill columns only the one with the smallest id is kept.
 *
 * @property oracle The oracle.
 * @property poolScenarioIds Valid scenarios (`scenario_config_id`), ascending; bit `p` of a kill
 *   set refers to `poolScenarioIds[p]`.
 * @property mutantIds Kept mutants (`mutants.id`), ascending.
 * @property kills Kill set per kept mutant, over the pool.
 * @property duplicateGroups Kept mutant -> all mutants with the same kill column (itself included),
 *   only for groups of two or more.
 * @property nonKillingMutantIds Mutants dropped because they kill nowhere.
 * @property baselineViolations Scenarios removed from the pool.
 * @property aiPilotKills AiPilot's kill set over the pool (outside check, not a mutant), or `null`.
 */
@Suppress("UseDataClass") // A data class would compare the arrays by reference in equals().
class KillMatrix(
    val oracle: KillOracle,
    val poolScenarioIds: IntArray,
    val mutantIds: IntArray,
    val kills: List<BitSet>,
    val duplicateGroups: Map<Int, List<Int>>,
    val nonKillingMutantIds: List<Int>,
    val baselineViolations: List<BaselineViolation>,
    val aiPilotKills: BitSet?,
)

/**
 * Per-oracle numbers written to `summary.json`.
 *
 * @property oracle Oracle name.
 * @property scenarios All scenarios.
 * @property pool Valid scenarios.
 * @property baselineViolations Scenarios removed because the baseline violates the oracle.
 * @property baselineViolationsAvoidedByAMutant Of those, scenarios where a mutant did not.
 * @property mutants Mutants before cleaning (AiPilot excluded).
 * @property nonKillingMutantIds Mutants dropped because they kill nowhere.
 * @property keptMutants Mutants after cleaning.
 * @property duplicateGroups See [KillMatrix.duplicateGroups].
 * @property killPairs Killing (scenario, kept mutant) pairs.
 * @property aiPilotKills Scenarios in the pool where AiPilot violates the oracle, or `null`.
 */
@Serializable
data class KillMatrixSummary(
    val oracle: String,
    val scenarios: Int,
    val pool: Int,
    val baselineViolations: Int,
    val baselineViolationsAvoidedByAMutant: Int,
    val mutants: Int,
    val nonKillingMutantIds: List<Int>,
    val keptMutants: Int,
    val duplicateGroups: Map<Int, List<Int>>,
    val killPairs: Long,
    val aiPilotKills: Int?,
)

/**
 * Contents of `summary.json`.
 *
 * @property thresholds Thresholds used.
 * @property oracles Numbers per oracle.
 * @property seconds Wall-clock time per stage.
 */
@Serializable
data class KillMatricesSummary(
    val thresholds: OracleThresholds,
    val oracles: List<KillMatrixSummary>,
    val seconds: Map<String, Double>,
)

/**
 * A [KillMatrix] as stored in `kill_matrix_<oracle>.json`; kill sets are lists of pool indices.
 *
 * @property oracle Oracle name.
 * @property thresholds Thresholds it was built with.
 * @property poolScenarioIds See [KillMatrix.poolScenarioIds].
 * @property mutantIds See [KillMatrix.mutantIds].
 * @property kills Per kept mutant, the pool indices of the scenarios that kill it, ascending.
 * @property duplicateGroups See [KillMatrix.duplicateGroups].
 * @property nonKillingMutantIds See [KillMatrix.nonKillingMutantIds].
 * @property baselineViolations See [KillMatrix.baselineViolations].
 * @property aiPilotKills AiPilot's kill set as pool indices, or `null`.
 */
@Serializable
data class StoredKillMatrix(
    val oracle: String,
    val thresholds: OracleThresholds,
    val poolScenarioIds: List<Int>,
    val mutantIds: List<Int>,
    val kills: List<List<Int>>,
    val duplicateGroups: Map<Int, List<Int>>,
    val nonKillingMutantIds: List<Int>,
    val baselineViolations: List<BaselineViolation>,
    val aiPilotKills: List<Int>?,
)

/**
 * Builds the kill matrices of all oracles from [ScenarioMutantOutcomes]' table and stores them, so
 * later steps and the page `postEvaluation/kill_matrices/index.html` need no database.
 */
object KillMatrices {

  /** Where everything is written; its `index.html` is committed, the rest is not. */
  const val OUTPUT_DIR: String = "postEvaluation/kill_matrices"

  private val json = Json { prettyPrint = true }
  private val compact = Json

  /**
   * Loads the outcomes, builds every oracle's kill matrix and writes to [OUTPUT_DIR]:
   * `kill_matrix_<oracle>.json` (the matrix), `summary.json` (the acceptance log),
   * `baseline_violations_<oracle>.csv` and `kill_rarity.json` (for the page).
   *
   * @param thresholds Oracle thresholds.
   */
  fun run(thresholds: OracleThresholds = OracleThresholds()) {
    val t0 = System.nanoTime()
    val data = OutcomeMatrices.load(includeAiPilot = true)
    val t1 = System.nanoTime()
    val matrices = KillOracle.entries.map { compute(data, it, thresholds) }
    val t2 = System.nanoTime()

    val dir = File(OUTPUT_DIR).apply { mkdirs() }
    for (m in matrices) {
      val name = m.oracle.name.lowercase()
      File(dir, "kill_matrix_$name.json")
          .writeText(compact.encodeToString(StoredKillMatrix.serializer(), toStored(m, thresholds)))
      File(dir, "baseline_violations_$name.csv")
          .writeText(
              buildString {
                appendLine("scenario_config_id,any_mutant_avoided")
                m.baselineViolations.forEach {
                  appendLine("${it.scenarioConfigId},${it.anyMutantAvoided}")
                }
              })
    }
    val mutants = data.mutantCount - (if (data.aiPilotRow != null) 1 else 0)
    val rarity = KillRarity.report(matrices, data.scenarioCount, mutants, thresholds)
    File(dir, "kill_rarity.json")
        .writeText(compact.encodeToString(KillRarityReport.serializer(), rarity))
    val t3 = System.nanoTime()

    val summary =
        KillMatricesSummary(
            thresholds,
            matrices.map { summarize(it, data) },
            mapOf(
                "load" to (t1 - t0) / 1e9,
                "compute" to (t2 - t1) / 1e9,
                "rarity" to (t3 - t2) / 1e9),
        )
    val summaryJson = json.encodeToString(KillMatricesSummary.serializer(), summary)
    File(dir, "summary.json").writeText(summaryJson)
    println(summaryJson)
    println("Wrote kill matrices, summary and kill_rarity.json to $dir; open $dir/index.html")
  }

  /** Reads the matrix of [oracle] that [run] stored in [dir]. */
  fun read(oracle: KillOracle, dir: File = File(OUTPUT_DIR)): KillMatrix =
      fromStored(
          compact.decodeFromString(
              StoredKillMatrix.serializer(),
              File(dir, "kill_matrix_${oracle.name.lowercase()}.json").readText()))

  /** [m] in its stored form. */
  fun toStored(m: KillMatrix, thresholds: OracleThresholds): StoredKillMatrix =
      StoredKillMatrix(
          oracle = m.oracle.name,
          thresholds = thresholds,
          poolScenarioIds = m.poolScenarioIds.toList(),
          mutantIds = m.mutantIds.toList(),
          kills = m.kills.map { it.stream().toArray().toList() },
          duplicateGroups = m.duplicateGroups,
          nonKillingMutantIds = m.nonKillingMutantIds,
          baselineViolations = m.baselineViolations,
          aiPilotKills = m.aiPilotKills?.stream()?.toArray()?.toList(),
      )

  /** The [KillMatrix] stored as [s]. */
  fun fromStored(s: StoredKillMatrix): KillMatrix {
    fun bits(indices: List<Int>) = BitSet().apply { indices.forEach { set(it) } }
    return KillMatrix(
        oracle = KillOracle.valueOf(s.oracle),
        poolScenarioIds = s.poolScenarioIds.toIntArray(),
        mutantIds = s.mutantIds.toIntArray(),
        kills = s.kills.map(::bits),
        duplicateGroups = s.duplicateGroups,
        nonKillingMutantIds = s.nonKillingMutantIds,
        baselineViolations = s.baselineViolations,
        aiPilotKills = s.aiPilotKills?.let(::bits),
    )
  }

  /** Builds the cleaned kill matrix of [oracle] from [data]. */
  fun compute(data: OutcomeMatrices, oracle: KillOracle, thresholds: OracleThresholds): KillMatrix {
    val n = data.scenarioCount
    val conditions = thresholds.conditions(oracle)
    val baseViolates =
        BooleanArray(n) { s ->
          if (oracle == KillOracle.G0) data.baselineAccident[s]
          else conditions.any { (m, t) -> data.baseline.getValue(m)[s] < t }
        }
    fun violates(i: Int): Boolean =
        if (oracle == KillOracle.G0) data.mutantAccident[i]
        else conditions.any { (m, t) -> data.mutant.getValue(m)[i] < t }

    val pool = (0 until n).filter { !baseViolates[it] }.toIntArray()
    val rows = (0 until data.mutantCount).filter { it != data.aiPilotRow }
    fun killSet(row: Int): BitSet =
        BitSet(pool.size).apply {
          pool.forEachIndexed { p, s -> if (violates(row * n + s)) set(p) }
        }

    val killSets = rows.associateWith(::killSet)
    val (killing, nonKilling) = rows.partition { !killSets.getValue(it).isEmpty }
    // Rows are in ascending id order, so the first member of each group has the smallest id.
    val groups = killing.groupBy { killSets.getValue(it) }.values.sortedBy { it.first() }

    return KillMatrix(
        oracle = oracle,
        poolScenarioIds = IntArray(pool.size) { data.scenarioIds[pool[it]] },
        mutantIds = IntArray(groups.size) { data.mutantIds[groups[it].first()] },
        kills = groups.map { killSets.getValue(it.first()) },
        duplicateGroups =
            groups
                .filter { it.size > 1 }
                .associate { g -> data.mutantIds[g.first()] to g.map { data.mutantIds[it] } },
        nonKillingMutantIds = nonKilling.map { data.mutantIds[it] },
        baselineViolations =
            (0 until n)
                .filter { baseViolates[it] }
                .map { s ->
                  BaselineViolation(data.scenarioIds[s], rows.any { !violates(it * n + s) })
                },
        aiPilotKills = data.aiPilotRow?.let(::killSet),
    )
  }

  private fun summarize(m: KillMatrix, data: OutcomeMatrices): KillMatrixSummary =
      KillMatrixSummary(
          oracle = m.oracle.name,
          scenarios = data.scenarioCount,
          pool = m.poolScenarioIds.size,
          baselineViolations = m.baselineViolations.size,
          baselineViolationsAvoidedByAMutant = m.baselineViolations.count { it.anyMutantAvoided },
          mutants = data.mutantCount - (if (data.aiPilotRow != null) 1 else 0),
          nonKillingMutantIds = m.nonKillingMutantIds,
          keptMutants = m.mutantIds.size,
          duplicateGroups = m.duplicateGroups,
          killPairs = m.kills.sumOf { it.cardinality().toLong() },
          aiPilotKills = m.aiPilotKills?.cardinality(),
      )
}
