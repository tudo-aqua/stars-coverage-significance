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
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.KillMatricesTable
import tools.aqua.stars.coverage.significance.db.tables.KillMatrixBaselineViolationsTable
import tools.aqua.stars.coverage.significance.db.tables.KillMatrixMutantsTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioMutantOutcomesTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
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
 * @property aiPilotMutantId AiPilot's `mutants.id`, or `null`.
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
    val aiPilotMutantId: Int? = null,
)

/** What cleaning made of a controller, as stored in [KillMatrixMutantsTable]. */
enum class KillMatrixRole {
  /** A column of the matrix. */
  KEPT,
  /** Same kill set as a kept mutant. */
  DUPLICATE,
  /** Kills nowhere. */
  NON_KILLING,
  /** Outside check, not a mutant. */
  AI_PILOT,
}

/**
 * One [KillMatrixMutantsTable] row.
 *
 * @property mutantId `mutants.id`.
 * @property role What cleaning made of it.
 * @property representativeMutantId The kept mutant of a [KillMatrixRole.DUPLICATE].
 * @property killScenarioIds Killing `scenario_config_id`s, ascending, for kept mutants and AiPilot.
 */
data class KillMatrixMutantRow(
    val mutantId: Int,
    val role: KillMatrixRole,
    val representativeMutantId: Int? = null,
    val killScenarioIds: List<Int>? = null,
)

/**
 * Per-oracle numbers written to `summary.json`.
 *
 * @property killMatrixId Id of the stored matrix in `kill_matrices`.
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
    val killMatrixId: Int,
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
 * Builds the kill matrices of all oracles from [ScenarioMutantOutcomes]' table and stores them in
 * [KillMatricesTable], [KillMatrixMutantsTable] and [KillMatrixBaselineViolationsTable]. The page
 * `postEvaluation/kill_matrices/index.html` reads the `kill_rarity.json` written next to it.
 */
object KillMatrices {

  /** Where the acceptance log and the page data are written; its `index.html` is committed. */
  const val OUTPUT_DIR: String = "postEvaluation/kill_matrices"

  private val json = Json { prettyPrint = true }
  private val compact = Json

  /** Every threshold written out, so the stored JSON can be compared and read from Python. */
  private val thresholdsJson = Json { encodeDefaults = true }

  /**
   * Loads the outcomes, builds every oracle's kill matrix, stores each as a new matrix in the
   * database and writes `summary.json` (the acceptance log) and `kill_rarity.json` (the page's
   * data) to [OUTPUT_DIR].
   *
   * @param thresholds Oracle thresholds.
   */
  fun run(thresholds: OracleThresholds = OracleThresholds()) {
    val t0 = System.nanoTime()
    val data = OutcomeMatrices.load(includeAiPilot = true)
    val lcWindowTicks = transaction {
      val t = ScenarioMutantOutcomesTable
      t.select(t.lcWindowTicks).withDistinct().map { it[t.lcWindowTicks] }.single()
    }
    val t1 = System.nanoTime()
    val matrices = KillOracle.entries.map { compute(data, it, thresholds) }
    val t2 = System.nanoTime()
    val ids = matrices.map { store(it, thresholds, lcWindowTicks, data.scenarioCount) }
    val t3 = System.nanoTime()

    val dir = File(OUTPUT_DIR).apply { mkdirs() }
    val mutants = data.mutantCount - (if (data.aiPilotRow != null) 1 else 0)
    val rarity = KillRarity.report(matrices, data.scenarioCount, mutants, thresholds)
    File(dir, "kill_rarity.json")
        .writeText(compact.encodeToString(KillRarityReport.serializer(), rarity))
    val t4 = System.nanoTime()

    val summary =
        KillMatricesSummary(
            thresholds,
            matrices.zip(ids) { m, id -> summarize(m, data, id) },
            mapOf(
                "load" to (t1 - t0) / 1e9,
                "compute" to (t2 - t1) / 1e9,
                "store" to (t3 - t2) / 1e9,
                "rarity" to (t4 - t3) / 1e9),
        )
    val summaryJson = json.encodeToString(KillMatricesSummary.serializer(), summary)
    File(dir, "summary.json").writeText(summaryJson)
    println(summaryJson)
    println(
        "Stored kill matrices ${KillOracle.entries.zip(ids)} (lane-change window $lcWindowTicks " +
            "ticks); wrote summary and kill_rarity.json to $dir; open $dir/index.html")
  }

  /**
   * Stores [m] as a new [KillMatricesTable] row with its mutants and violations; returns its id.
   */
  private fun store(
      m: KillMatrix,
      thresholds: OracleThresholds,
      lcWindowTicks: Int,
      scenarioCount: Int
  ): Int = transaction {
    SchemaUtils.createMissingTablesAndColumns(
        KillMatricesTable, KillMatrixMutantsTable, KillMatrixBaselineViolationsTable)
    val id =
        KillMatricesTable.insertAndGetId {
          it[oracle] = m.oracle.name
          it[this.thresholdsJson] = encodeThresholds(thresholds)
          it[this.lcWindowTicks] = lcWindowTicks
          it[nScenarios] = scenarioCount
          it[nPool] = m.poolScenarioIds.size
        }
    KillMatrixMutantsTable.batchInsert(mutantRows(m), shouldReturnGeneratedValues = false) { r ->
      this[KillMatrixMutantsTable.killMatrixId] = id
      this[KillMatrixMutantsTable.mutantId] = r.mutantId
      this[KillMatrixMutantsTable.role] = r.role.name
      this[KillMatrixMutantsTable.representativeMutantId] = r.representativeMutantId
      this[KillMatrixMutantsTable.killScenarioIds] = r.killScenarioIds
    }
    KillMatrixBaselineViolationsTable.batchInsert(
        m.baselineViolations, shouldReturnGeneratedValues = false) { v ->
          this[KillMatrixBaselineViolationsTable.killMatrixId] = id
          this[KillMatrixBaselineViolationsTable.scenarioConfigId] = v.scenarioConfigId
          this[KillMatrixBaselineViolationsTable.anyMutantAvoided] = v.anyMutantAvoided
        }
    id.value
  }

  /** [thresholds] as stored in [KillMatricesTable.thresholdsJson]. */
  fun encodeThresholds(thresholds: OracleThresholds): String =
      thresholdsJson.encodeToString(OracleThresholds.serializer(), thresholds)

  /**
   * Id of the newest stored matrix of [oracle], optionally only among those built with
   * [thresholds].
   */
  fun latestId(oracle: KillOracle, thresholds: OracleThresholds? = null): Int = transaction {
    val t = KillMatricesTable
    t.select(t.id)
        .where {
          (t.oracle eq oracle.name) and
              (thresholds?.let { t.thresholdsJson eq encodeThresholds(it) } ?: Op.TRUE)
        }
        .maxOfOrNull { it[t.id].value }
        ?: error("No stored $oracle kill matrix; run runKillMatrices")
  }

  /** Reads the stored matrix [killMatrixId]. */
  fun read(killMatrixId: Int): KillMatrix = transaction {
    val oracle =
        KillMatricesTable.select(KillMatricesTable.oracle)
            .where { KillMatricesTable.id eq killMatrixId }
            .single()[KillMatricesTable.oracle]
    val mt = KillMatrixMutantsTable
    val rows =
        mt.selectAll()
            .where { mt.killMatrixId eq killMatrixId }
            .map {
              KillMatrixMutantRow(
                  it[mt.mutantId].value,
                  KillMatrixRole.valueOf(it[mt.role]),
                  it[mt.representativeMutantId],
                  it[mt.killScenarioIds])
            }
    val vt = KillMatrixBaselineViolationsTable
    val violations =
        vt.selectAll()
            .where { vt.killMatrixId eq killMatrixId }
            .map { BaselineViolation(it[vt.scenarioConfigId].value, it[vt.anyMutantAvoided]) }
    val sc = ScenarioStartingConfigurationTable
    val scenarioIds = sc.select(sc.id).map { it[sc.id].value }.toIntArray()
    fromRows(KillOracle.valueOf(oracle), scenarioIds, rows, violations)
  }

  /** [m]'s controllers as [KillMatrixMutantsTable] rows. */
  fun mutantRows(m: KillMatrix): List<KillMatrixMutantRow> {
    fun ids(kills: BitSet) = kills.stream().map { m.poolScenarioIds[it] }.toArray().toList()
    val kept =
        m.mutantIds.mapIndexed { i, id ->
          KillMatrixMutantRow(id, KillMatrixRole.KEPT, null, ids(m.kills[i]))
        }
    val duplicates =
        m.duplicateGroups.flatMap { (rep, group) ->
          group.filter { it != rep }.map { KillMatrixMutantRow(it, KillMatrixRole.DUPLICATE, rep) }
        }
    val nonKilling =
        m.nonKillingMutantIds.map { KillMatrixMutantRow(it, KillMatrixRole.NON_KILLING) }
    val aiPilot =
        m.aiPilotMutantId?.let {
          listOf(KillMatrixMutantRow(it, KillMatrixRole.AI_PILOT, null, m.aiPilotKills?.let(::ids)))
        } ?: emptyList()
    return kept + duplicates + nonKilling + aiPilot
  }

  /**
   * The [KillMatrix] stored as [rows] and [violations]; the pool is [scenarioIds] minus the
   * violations.
   */
  fun fromRows(
      oracle: KillOracle,
      scenarioIds: IntArray,
      rows: List<KillMatrixMutantRow>,
      violations: List<BaselineViolation>,
  ): KillMatrix {
    val removed = violations.map { it.scenarioConfigId }.toSet()
    val pool = scenarioIds.filter { it !in removed }.sorted().toIntArray()
    fun bits(ids: List<Int>?) =
        BitSet(pool.size).apply {
          ids.orEmpty().forEach { set(pool.binarySearch(it).also { p -> check(p >= 0) }) }
        }
    val byRole = rows.groupBy { it.role }
    val kept = byRole[KillMatrixRole.KEPT].orEmpty().sortedBy { it.mutantId }
    val duplicates =
        byRole[KillMatrixRole.DUPLICATE].orEmpty().groupBy { it.representativeMutantId!! }
    val aiPilot = byRole[KillMatrixRole.AI_PILOT]?.single()
    return KillMatrix(
        oracle = oracle,
        poolScenarioIds = pool,
        mutantIds = kept.map { it.mutantId }.toIntArray(),
        kills = kept.map { bits(it.killScenarioIds) },
        duplicateGroups =
            duplicates.entries
                .sortedBy { it.key }
                .associate { (rep, dups) ->
                  rep to (listOf(rep) + dups.map { it.mutantId }).sorted()
                },
        nonKillingMutantIds =
            byRole[KillMatrixRole.NON_KILLING].orEmpty().map { it.mutantId }.sorted(),
        baselineViolations = violations.sortedBy { it.scenarioConfigId },
        aiPilotKills = aiPilot?.let { bits(it.killScenarioIds) },
        aiPilotMutantId = aiPilot?.mutantId,
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
        aiPilotMutantId = data.aiPilotRow?.let { data.mutantIds[it] },
    )
  }

  private fun summarize(m: KillMatrix, data: OutcomeMatrices, id: Int): KillMatrixSummary =
      KillMatrixSummary(
          killMatrixId = id,
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
