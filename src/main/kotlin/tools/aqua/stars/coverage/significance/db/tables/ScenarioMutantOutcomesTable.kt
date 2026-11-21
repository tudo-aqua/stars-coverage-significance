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

package tools.aqua.stars.coverage.significance.db.tables

import org.jetbrains.exposed.sql.Table

/**
 * One row per (starting scenario, controller): the run's accident flag and the smallest own-lane
 * TTC and TG, built from [MetricFailedMonitorsTable] by
 * [tools.aqua.stars.coverage.significance.postEvaluation.scenario.ScenarioMutantOutcomes] (step A.1
 * of `docs/test-bed-plan.md`).
 *
 * A `null` in one of the `min*` columns means the value was never finite during the run, i.e.
 * +infinity: it never falls below any threshold.
 *
 * @property scenarioConfigId Id in [ScenarioStartingConfigurationTable].
 * @property mutantId Id in [MutantsTable] (the baseline has `mutant_number = -1`).
 * @property accidentAny Whether G0 (accident) failed at any tick.
 * @property firstAccidentTick First tick at which G0 failed, or `null`.
 * @property minFrontTtc Smallest TTC to the vehicle in front on the ego's lane (s).
 * @property minFrontTg Smallest time gap to the vehicle in front on the ego's lane (s).
 * @property minRearTtc Smallest TTC of the vehicle behind on the ego's lane (s).
 * @property minRearTg Smallest time gap of the vehicle behind on the ego's lane (s).
 * @property minRearTtcAfterLc Like [minRearTtc], over ticks within [lcWindowTicks] after an ego
 *   lane change only.
 * @property minRearTgAfterLc Like [minRearTg], over ticks within [lcWindowTicks] after an ego lane
 *   change only.
 * @property nLaneChanges Number of ticks at which `ego_lane` differs from the previous tick.
 * @property nTicks Number of ticks of the run.
 * @property nRuns Number of (tsc, evaluation run) combinations that contributed ticks; above 1
 *   means duplicate runs were merged.
 * @property lcWindowTicks Lane-change window used for the `*AfterLc` columns.
 */
object ScenarioMutantOutcomesTable : Table("scenario_mutant_outcomes") {
  val scenarioConfigId = integer("scenario_config_id")
  val mutantId = integer("mutant_id")
  val accidentAny = bool("accident_any")
  val firstAccidentTick = long("first_accident_tick").nullable()
  val minFrontTtc = float("min_front_ttc").nullable()
  val minFrontTg = float("min_front_tg").nullable()
  val minRearTtc = float("min_rear_ttc").nullable()
  val minRearTg = float("min_rear_tg").nullable()
  val minRearTtcAfterLc = float("min_rear_ttc_after_lc").nullable()
  val minRearTgAfterLc = float("min_rear_tg_after_lc").nullable()
  val nLaneChanges = long("n_lane_changes")
  val nTicks = long("n_ticks")
  val nRuns = long("n_runs")
  val lcWindowTicks = integer("lc_window_ticks")

  init {
    // Same name as the index the earlier SQL script created, so an existing table is reused as is.
    uniqueIndex("scenario_mutant_outcomes_pk", scenarioConfigId, mutantId)
  }
}
