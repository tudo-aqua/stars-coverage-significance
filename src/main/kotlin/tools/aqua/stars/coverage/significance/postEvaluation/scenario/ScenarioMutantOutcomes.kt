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

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioMutantOutcomesTable

/** Which controllers one build of [ScenarioMutantOutcomesTable] covers. */
enum class OutcomeScope {
  /** Only the unmutated autopilot (`mutant_number = -1`); about 8M ticks. */
  BASELINE,
  /** Every other controller, including AiPilot; about 1.4B ticks. */
  MUTANTS,
  /** Both. */
  ALL,
}

/**
 * Builds [ScenarioMutantOutcomesTable] from `metric_failed_monitors` in one pass (step A.1 of
 * `docs/test-bed-plan.md`).
 *
 * Each build first deletes the rows of its [OutcomeScope] and then inserts them again, in one
 * transaction, so a scope can be rebuilt on its own.
 */
object ScenarioMutantOutcomes {

  /** Ticks after an ego lane change that count for the `*AfterLc` columns (2 s at 100 ms). */
  const val DEFAULT_LANE_CHANGE_WINDOW_TICKS: Int = 20

  /** Class name of the outside-check controller, which is not a mutant of the autopilot. */
  const val AI_PILOT_CLASS_NAME: String = "AiPilot"

  /**
   * Rebuilds the rows of [scope] and prints the acceptance checks.
   *
   * @param scope Which controllers to (re)build.
   * @param laneChangeWindowTicks Ticks after an ego lane change that count for the rear columns
   *   `*AfterLc`.
   */
  fun build(
      scope: OutcomeScope,
      laneChangeWindowTicks: Int = DEFAULT_LANE_CHANGE_WINDOW_TICKS,
  ) {
    require(laneChangeWindowTicks >= 0) { "laneChangeWindowTicks must not be negative" }
    val t = ScenarioMutantOutcomesTable
    transaction {
      SchemaUtils.create(t)
      val ids = mutantIds(scope)
      check(ids.isNotEmpty()) { "No controllers for scope $scope" }
      println("Building scenario_mutant_outcomes for $scope (${ids.size} controllers) ...")

      val start = System.nanoTime()
      val deleted = t.deleteWhere { mutantId inList ids }
      exec(insertSql(ids, laneChangeWindowTicks))
      println(
          "Deleted $deleted old rows and inserted the new ones in ${seconds(start)} s " +
              "(lane-change window $laneChangeWindowTicks ticks)")
    }

    transaction {
      val ids = mutantIds(scope)
      val inScope = t.mutantId inList ids
      val pairs = t.selectAll().where { inScope }.count()
      val scenarios = t.select(t.scenarioConfigId).where { inScope }.withDistinct().count()
      val severalRuns = t.selectAll().where { inScope and (t.nRuns greater 1L) }.count()
      val accidents = t.selectAll().where { inScope and (t.accidentAny eq true) }.count()
      println(
          "Check for $scope: $pairs pairs, $scenarios scenarios, ${ids.size} controllers, " +
              "$severalRuns pairs with several runs, $accidents pairs with an accident")
    }
  }

  /** Ids in [MutantsTable] covered by [scope]. */
  private fun mutantIds(scope: OutcomeScope): List<Int> =
      MutantsTable.select(MutantsTable.id, MutantsTable.mutantNumber)
          .map { it[MutantsTable.id].value to (it[MutantsTable.mutantNumber] == -1) }
          .filter { (_, isBaseline) ->
            when (scope) {
              OutcomeScope.BASELINE -> isBaseline
              OutcomeScope.MUTANTS -> !isBaseline
              OutcomeScope.ALL -> true
            }
          }
          .map { it.first }

  /**
   * The aggregation as SQL. It needs `LAG` and a running `MAX` over each run's ticks plus `FILTER`
   * aggregates, inside an `INSERT ... SELECT`, which the Exposed DSL cannot express (same reason as
   * the materialized views in `DbBootstrap`). Only integers are interpolated.
   */
  private fun insertSql(mutantIds: List<Int>, laneChangeWindowTicks: Int): String =
      """
      INSERT INTO scenario_mutant_outcomes
          (scenario_config_id, mutant_id, accident_any, first_accident_tick,
           min_front_ttc, min_front_tg, min_rear_ttc, min_rear_tg,
           min_rear_ttc_after_lc, min_rear_tg_after_lc,
           n_lane_changes, n_ticks, n_runs, lc_window_ticks)
      WITH ticks AS (
          SELECT m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id, m.tick,
                 m."monitor_g0_Accidents_failed" AS g0,
                 m.surrounding_front_ttc_s AS front_ttc, m.surrounding_front_tg_s AS front_tg,
                 m.surrounding_rear_ttc_s  AS rear_ttc,  m.surrounding_rear_tg_s  AS rear_tg,
                 m.ego_lane <> LAG(m.ego_lane) OVER w AS lane_changed,
                 -- tick is in milliseconds, so the window counts rows of the run, not tick values.
                 ROW_NUMBER() OVER w AS tick_index
          FROM metric_failed_monitors m
          WHERE m.mutant_id = ANY(ARRAY[${mutantIds.joinToString(",")}]::int[])
          -- Same column order as the unique index, so Postgres can read it pre-sorted.
          WINDOW w AS (PARTITION BY m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id
                       ORDER BY m.tick)
      ),
      marked AS (
          SELECT t.*,
                 t.tick_index - MAX(CASE WHEN t.lane_changed THEN t.tick_index END) OVER (
                     PARTITION BY t.tsc_id, t.run_id, t.scenario_config_id, t.mutant_id
                     ORDER BY t.tick) <= $laneChangeWindowTicks AS after_lc
          FROM ticks t
      )
      SELECT scenario_config_id, mutant_id,
             BOOL_OR(g0),
             MIN(tick) FILTER (WHERE g0),
             MIN(front_ttc), MIN(front_tg), MIN(rear_ttc), MIN(rear_tg),
             MIN(rear_ttc) FILTER (WHERE after_lc),
             MIN(rear_tg) FILTER (WHERE after_lc),
             COUNT(*) FILTER (WHERE lane_changed),
             COUNT(*),
             COUNT(DISTINCT (tsc_id, run_id)),
             $laneChangeWindowTicks
      FROM marked
      GROUP BY scenario_config_id, mutant_id
      """
          .trimIndent()

  private fun seconds(startNanos: Long): String =
      "%.1f".format((System.nanoTime() - startNanos) / 1e9)
}
