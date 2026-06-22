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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * One row from [MutantScenarioG0ViolationsView].
 *
 * @property mutantId ID of the mutant.
 * @property scenarioConfigId ID of the scenario starting configuration.
 * @property anyG0Violation `true` if the G0 accident monitor fired in the next tick for at least
 *   one tick in this (mutant, scenario_config) pair; `false` otherwise.
 */
data class MutantScenarioG0Violation(
    val mutantId: Int,
    val scenarioConfigId: Int,
    val anyG0Violation: Boolean,
)

/**
 * Exposed mapping for the `mutant_scenario_g0_violations` PostgreSQL view.
 *
 * The view aggregates [MetricFailedMonitorsTable] by (mutant, scenario_config) and exposes a single
 * boolean per pair: whether [MetricFailedMonitorsTable.nextTickMonitorG0Failed] was `true` for at
 * least one tick.
 *
 * The view DDL is:
 * ```sql
 * CREATE OR REPLACE VIEW mutant_scenario_g0_violations AS
 * SELECT "mutant_id",
 *        "scenario_config_id",
 *        COALESCE(BOOL_OR("next_tick_monitor_g0_Accidents_failed"), false) AS any_g0_violation
 * FROM metric_failed_monitors
 * GROUP BY "mutant_id", "scenario_config_id"
 * ```
 *
 * The view is created (or replaced) at schema bootstrap time by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.createSchema].
 */
object MutantScenarioG0ViolationsView : Table("mutant_scenario_g0_violations") {

  /** Foreign key to [MutantsTable]: the mutant evaluated in this pair. */
  val mutantId = integer("mutant_id")

  /** Foreign key to [ScenarioStartingConfigurationTable]: the scenario configuration. */
  val scenarioConfigId = integer("scenario_config_id")

  /**
   * `true` if at least one tick in this (mutant, scenario_config) pair had
   * [MetricFailedMonitorsTable.nextTickMonitorG0Failed] = `true`; `false` otherwise (including when
   * all ticks are the last tick of a run and the column is `null`).
   */
  val anyG0Violation = bool("any_g0_violation")

  override val primaryKey = PrimaryKey(mutantId, scenarioConfigId)

  /**
   * Returns all rows from the view.
   *
   * For large datasets this may transfer many rows; prefer filtering at the call site if only a
   * subset is needed.
   */
  fun getAll(): List<MutantScenarioG0Violation> = transaction {
    selectAll().map { row ->
      MutantScenarioG0Violation(
          mutantId = row[mutantId],
          scenarioConfigId = row[scenarioConfigId],
          anyG0Violation = row[anyG0Violation],
      )
    }
  }
}
