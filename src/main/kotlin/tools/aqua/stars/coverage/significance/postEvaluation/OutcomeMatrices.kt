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

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioMutantOutcomesTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable

internal const val TTC = "ttc"
internal const val TG = "tg"
private const val FRONT = "front"
private const val REAR = "rear"
private const val ALWAYS = "always"
private const val AFTER_LANE_CHANGE = "after_lane_change"

/**
 * One `min*` column of [ScenarioMutantOutcomesTable] used as an oracle input.
 *
 * @property oracle `ttc` or `tg`.
 * @property side `front` or `rear`.
 * @property mode `always`, or `after_lane_change` (only ticks shortly after an ego lane change).
 * @property column The column holding the run's smallest value.
 */
enum class OracleMeasure(
    val oracle: String,
    val side: String,
    val mode: String,
    val column: Column<Float?>,
) {
  /** TTC to the vehicle in front. */
  TTC_FRONT(TTC, FRONT, ALWAYS, ScenarioMutantOutcomesTable.minFrontTtc),
  /** TTC of the vehicle behind, all ticks. */
  TTC_REAR(TTC, REAR, ALWAYS, ScenarioMutantOutcomesTable.minRearTtc),
  /** TTC of the vehicle behind, only after an ego lane change. */
  TTC_REAR_AFTER_LC(TTC, REAR, AFTER_LANE_CHANGE, ScenarioMutantOutcomesTable.minRearTtcAfterLc),
  /** Time gap to the vehicle in front. */
  TG_FRONT(TG, FRONT, ALWAYS, ScenarioMutantOutcomesTable.minFrontTg),
  /** Time gap of the vehicle behind, all ticks. */
  TG_REAR(TG, REAR, ALWAYS, ScenarioMutantOutcomesTable.minRearTg),
  /** Time gap of the vehicle behind, only after an ego lane change. */
  TG_REAR_AFTER_LC(TG, REAR, AFTER_LANE_CHANGE, ScenarioMutantOutcomesTable.minRearTgAfterLc),
}

/**
 * [ScenarioMutantOutcomesTable] in memory: per-scenario vectors for the baseline and
 * mutant-by-scenario matrices (index `mutant * scenarioCount + scenario`) for the other
 * controllers. Missing values are +infinity (never a violation) and `false`.
 *
 * @property scenarioCount Number of starting scenarios.
 * @property mutantCount Number of matrix rows (baseline excluded).
 * @property baselineAccident Whether the baseline had an accident, per scenario.
 * @property baselineLaneChanged Whether the baseline changed lanes, per scenario.
 * @property baseline Baseline minimum per [OracleMeasure], per scenario.
 * @property mutantAccident Whether the controller had an accident, per (row, scenario).
 * @property mutant Controller minimum per [OracleMeasure], per (row, scenario).
 * @property scenarioIds `scenario_config_id` per scenario index, ascending.
 * @property mutantIds `mutants.id` per row, ascending.
 * @property aiPilotRow Row of AiPilot, or `null` if it was not loaded.
 */
@Suppress("UseDataClass") // A data class would compare the arrays by reference in equals().
class OutcomeMatrices(
    val scenarioCount: Int,
    val mutantCount: Int,
    val baselineAccident: BooleanArray,
    val baselineLaneChanged: BooleanArray,
    val baseline: Map<OracleMeasure, FloatArray>,
    val mutantAccident: BooleanArray,
    val mutant: Map<OracleMeasure, FloatArray>,
    val scenarioIds: IntArray = IntArray(scenarioCount) { it },
    val mutantIds: IntArray = IntArray(mutantCount) { it },
    val aiPilotRow: Int? = null,
) {
  /** G0 kills: the controller has an accident and the baseline does not (D9). */
  val g0Kills: BooleanArray =
      BooleanArray(mutantCount * scenarioCount) {
        mutantAccident[it] && !baselineAccident[it % scenarioCount]
      }

  /** Loading from the database. */
  companion object {
    private const val CHUNK_SCENARIOS = 2048

    /**
     * Reads [ScenarioMutantOutcomesTable] in chunks of scenario ids.
     *
     * @param includeAiPilot Whether AiPilot gets a row (see [aiPilotRow]); otherwise it is skipped.
     */
    fun load(includeAiPilot: Boolean = false): OutcomeMatrices = transaction {
      val sc = ScenarioStartingConfigurationTable
      val scenarioIds = sc.select(sc.id).map { it[sc.id].value }.sorted().toIntArray()
      val controllers =
          MutantsTable.select(MutantsTable.id, MutantsTable.mutantNumber, MutantsTable.className)
              .map {
                Triple(
                    it[MutantsTable.id].value,
                    it[MutantsTable.mutantNumber],
                    it[MutantsTable.className])
              }
      val baselineId = controllers.single { it.second == -1 }.first
      val aiPilotIds =
          controllers
              .filter { it.third == ScenarioMutantOutcomes.AI_PILOT_CLASS_NAME }
              .map { it.first }
      val mutantIds =
          controllers
              .filter { it.second != -1 && (includeAiPilot || it.first !in aiPilotIds) }
              .map { it.first }
              .sorted()
              .toIntArray()
      val mutantRow = mutantIds.withIndex().associate { (row, id) -> id to row }

      val n = scenarioIds.size
      val cells = mutantIds.size * n
      val baseAccident = BooleanArray(n)
      val baseLaneChanged = BooleanArray(n)
      val base = OracleMeasure.entries.associateWith { FloatArray(n) { Float.POSITIVE_INFINITY } }
      val mutAccident = BooleanArray(cells)
      val mut =
          OracleMeasure.entries.associateWith { FloatArray(cells) { Float.POSITIVE_INFINITY } }
      var baseFilled = 0
      var mutFilled = 0L

      val t = ScenarioMutantOutcomesTable
      val columns =
          listOf(t.scenarioConfigId, t.mutantId, t.accidentAny, t.nLaneChanges) +
              OracleMeasure.entries.map { it.column }
      for (chunk in scenarioIds.asList().chunked(CHUNK_SCENARIOS)) {
        t.select(columns)
            .where { t.scenarioConfigId.between(chunk.first(), chunk.last()) }
            .forEach { r ->
              val s = scenarioIds.binarySearch(r[t.scenarioConfigId])
              val id = r[t.mutantId]
              if (id == baselineId) {
                baseAccident[s] = r[t.accidentAny]
                baseLaneChanged[s] = r[t.nLaneChanges] > 0
                for ((m, col) in base) col[s] = r[m.column] ?: Float.POSITIVE_INFINITY
                baseFilled++
              } else {
                val row = mutantRow[id] ?: return@forEach // AiPilot, not requested
                val i = row * n + s
                mutAccident[i] = r[t.accidentAny]
                for ((m, col) in mut) col[i] = r[m.column] ?: Float.POSITIVE_INFINITY
                mutFilled++
              }
            }
      }
      if (baseFilled != n || mutFilled != cells.toLong()) {
        // Missing rows count as "no violation"; say so instead of hiding it.
        println(
            "WARNING: ${n - baseFilled} scenarios without a baseline row, " +
                "${cells - mutFilled} missing (scenario, controller) pairs")
      }
      OutcomeMatrices(
          n,
          mutantIds.size,
          baseAccident,
          baseLaneChanged,
          base,
          mutAccident,
          mut,
          scenarioIds,
          mutantIds,
          aiPilotIds.firstNotNullOfOrNull { mutantRow[it] },
      )
    }
  }
}
