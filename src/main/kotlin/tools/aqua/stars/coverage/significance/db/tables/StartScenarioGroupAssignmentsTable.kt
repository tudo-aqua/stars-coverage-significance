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

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * The group of every starting scenario under a [StartScenarioGroupingRunsTable] row (D3: labels go
 * to starting scenarios, not ticks).
 *
 * @property runId The grouping run.
 * @property scenarioConfigId The starting scenario.
 * @property groupId LightGBM leaf index for a tree, [TSCInstancesTable] id for a TSC.
 */
object StartScenarioGroupAssignmentsTable : Table("start_scenario_group_assignments") {
  val runId =
      reference("run_id", StartScenarioGroupingRunsTable, onDelete = ReferenceOption.CASCADE)
  val scenarioConfigId = reference("scenario_config_id", ScenarioStartingConfigurationTable)
  val groupId = integer("group_id")

  override val primaryKey = PrimaryKey(runId, scenarioConfigId)
}
