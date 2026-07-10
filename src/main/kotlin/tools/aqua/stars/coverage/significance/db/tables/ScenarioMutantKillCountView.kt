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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.StartingScenarioId

/**
 * One row from [ScenarioMutantKillCountView].
 *
 * @property scenarioConfigId ID of the scenario starting configuration.
 * @property mutantsKilled Number of distinct mutants for which
 *   [anyG0Violation][MutantScenarioG0ViolationsView] is `true` in this scenario.
 */
data class ScenarioMutantKillCount(
    val scenarioConfigId: StartingScenarioId,
    val mutantsKilled: Long,
)

/**
 * Exposed mapping for the `scenario_mutant_kill_count` PostgreSQL materialized view.
 *
 * The view aggregates [MutantScenarioG0ViolationsView] by scenario and counts how many mutants are
 * killed (i.e. have `any_g0_violation = true`) in each scenario.
 *
 * The materialized view DDL is:
 * ```sql
 * CREATE MATERIALIZED VIEW IF NOT EXISTS scenario_mutant_kill_count AS
 * SELECT scenario_config_id,
 *        SUM(CASE WHEN any_g0_violation THEN 1 ELSE 0 END) AS mutants_killed
 * FROM mutant_scenario_g0_violations
 * GROUP BY scenario_config_id
 * ```
 *
 * The view is created (or replaced) at schema bootstrap time by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.createSchema].
 */
object ScenarioMutantKillCountView : Table("scenario_mutant_kill_count") {

  /** Foreign key to [ScenarioStartingConfigurationTable]: the scenario configuration. */
  val scenarioConfigId = integer("scenario_config_id")

  /** Number of mutants killed (any_g0_violation = true) in this scenario. */
  val mutantsKilled = long("mutants_killed")

  override val primaryKey = PrimaryKey(scenarioConfigId)

  /** Returns all rows from the view. */
  fun getAll(): List<ScenarioMutantKillCount> = transaction {
    selectAll().map { row ->
      ScenarioMutantKillCount(
          scenarioConfigId = row[scenarioConfigId],
          mutantsKilled = row[mutantsKilled],
      )
    }
  }
}
