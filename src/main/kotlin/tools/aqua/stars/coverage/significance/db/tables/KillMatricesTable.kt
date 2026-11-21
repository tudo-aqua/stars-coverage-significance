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

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One kill matrix per row (steps A.2 and A.3 of `docs/test-bed-plan.md`), built by
 * [tools.aqua.stars.coverage.significance.postEvaluation.scenario.KillMatrices]. Rebuilding adds
 * new rows, so tree runs keep pointing at the matrix they were trained on.
 *
 * The pool (valid scenarios) is every scenario in [ScenarioStartingConfigurationTable] minus the
 * matrix's rows in [KillMatrixBaselineViolationsTable].
 *
 * @property createdAt When the matrix was built.
 * @property oracle `G0`, `TTC` or `TG`.
 * @property thresholdsJson The `OracleThresholds` used, as JSON with every field written out.
 * @property lcWindowTicks Lane-change window of the outcome table the matrix was built from.
 * @property nScenarios All scenarios.
 * @property nPool Valid scenarios.
 */
object KillMatricesTable : IntIdTable("kill_matrices") {
  val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
  val oracle = varchar("oracle", 8)
  val thresholdsJson = text("thresholds_json")
  val lcWindowTicks = integer("lc_window_ticks")
  val nScenarios = integer("n_scenarios")
  val nPool = integer("n_pool")
}
