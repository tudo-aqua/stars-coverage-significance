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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.StartingScenarioId

/**
 * One row from [DcStartingScenarioMutantCombinationView].
 *
 * @property id Primary key of the underlying [MetricFailedMonitorsTable] row.
 * @property tick Simulation tick index of the metric row.
 * @property mutantId ID of the mutant.
 * @property scenarioConfigId ID of the scenario starting configuration.
 * @property leafNodeId Decision-tree leaf node assigned to this tick row.
 * @property anyG0Violation `true` if the G0 accident monitor fired at least once for this (mutant,
 *   scenario) pair across all ticks.
 */
data class DcStartingScenarioMutantCombination(
    val id: Int,
    val tick: Long,
    val mutantId: MutantId,
    val scenarioConfigId: StartingScenarioId,
    val leafNodeId: Int,
    val anyG0Violation: Boolean,
)

/**
 * Exposed mapping for the `dc_startingscenario_mutant_combination` PostgreSQL materialized view.
 *
 * The view joins [MetricFailedMonitorsTable], [DecisionTreeLeafAssignmentsTable], and
 * [MutantScenarioG0ViolationsView] to expose per-tick rows enriched with the leaf node assignment
 * and the aggregated G0-violation flag for the corresponding (mutant, scenario) pair.
 *
 * The materialized view DDL is:
 * ```sql
 * CREATE MATERIALIZED VIEW IF NOT EXISTS dc_startingscenario_mutant_combination AS
 * SELECT metric_failed_monitors.id,
 *        metric_failed_monitors.tick,
 *        metric_failed_monitors.mutant_id,
 *        metric_failed_monitors.scenario_config_id,
 *        decision_tree_leaf_assignments.leaf_node_id,
 *        mutant_scenario_g0_violations.any_g0_violation
 * FROM metric_failed_monitors
 *          JOIN decision_tree_leaf_assignments
 *               ON metric_failed_monitors.id = decision_tree_leaf_assignments.metric_failed_monitor_id
 *          JOIN mutant_scenario_g0_violations
 *               ON metric_failed_monitors.mutant_id = mutant_scenario_g0_violations.mutant_id
 *              AND metric_failed_monitors.scenario_config_id = mutant_scenario_g0_violations.scenario_config_id
 * WHERE decision_tree_leaf_assignments.run_id = 3
 * ```
 *
 * The view is created (or replaced) at schema bootstrap time by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.createSchema].
 */
object DcStartingScenarioMutantCombinationView : Table("dc_startingscenario_mutant_combination") {

  /** Primary key of the underlying [MetricFailedMonitorsTable] row. */
  val id = integer("id")

  /** Simulation tick index. */
  val tick = long("tick")

  /** Foreign key to [MutantsTable]: the mutant evaluated in this row. */
  val mutantId = integer("mutant_id")

  /** Foreign key to [ScenarioStartingConfigurationTable]: the scenario configuration. */
  val scenarioConfigId = integer("scenario_config_id")

  /** Decision-tree leaf node assigned by the run referenced in the view's WHERE clause. */
  val leafNodeId = integer("leaf_node_id")

  /**
   * `true` if the G0 accident monitor fired at least once for this (mutant, scenario) pair; sourced
   * from [MutantScenarioG0ViolationsView].
   */
  val anyG0Violation = bool("any_g0_violation")

  /** Returns all rows from the view. */
  fun getAll(): List<DcStartingScenarioMutantCombination> {
    val idCol = id
    val tickCol = tick
    val mutantIdCol = mutantId
    val scenarioConfigIdCol = scenarioConfigId
    val leafNodeIdCol = leafNodeId
    val anyG0ViolationCol = anyG0Violation
    return transaction {
      selectAll().map { row ->
        DcStartingScenarioMutantCombination(
            id = row[idCol],
            tick = row[tickCol],
            mutantId = row[mutantIdCol],
            scenarioConfigId = row[scenarioConfigIdCol],
            leafNodeId = row[leafNodeIdCol],
            anyG0Violation = row[anyG0ViolationCol],
        )
      }
    }
  }
}
