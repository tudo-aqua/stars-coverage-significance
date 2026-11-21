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
 * Scenarios the baseline violates under a [KillMatricesTable] row's oracle; they are not in its
 * pool (D9, step A.2 of `docs/test-bed-plan.md`).
 *
 * @property killMatrixId The matrix.
 * @property scenarioConfigId The removed scenario.
 * @property anyMutantAvoided Whether at least one mutant did not violate the oracle there.
 */
object KillMatrixBaselineViolationsTable : Table("kill_matrix_baseline_violations") {
  val killMatrixId =
      reference("kill_matrix_id", KillMatricesTable, onDelete = ReferenceOption.CASCADE)
  val scenarioConfigId = reference("scenario_config_id", ScenarioStartingConfigurationTable)
  val anyMutantAvoided = bool("any_mutant_avoided")

  override val primaryKey = PrimaryKey(killMatrixId, scenarioConfigId)
}
