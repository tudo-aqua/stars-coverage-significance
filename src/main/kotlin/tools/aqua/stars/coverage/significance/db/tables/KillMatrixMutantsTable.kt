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

import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Every controller of a [KillMatricesTable] row and what cleaning made of it.
 *
 * @property killMatrixId The matrix.
 * @property mutantId Id in [MutantsTable].
 * @property role `KEPT` (a column of the matrix), `DUPLICATE` (same kill set as
 *   [representativeMutantId]), `NON_KILLING` (kills nowhere) or `AI_PILOT` (outside check).
 * @property representativeMutantId The kept mutant of a `DUPLICATE`, else `null`.
 * @property killScenarioIds `scenario_config_id`s that kill it, ascending, for `KEPT` and
 *   `AI_PILOT`; `null` otherwise.
 */
object KillMatrixMutantsTable : Table("kill_matrix_mutants") {
  val killMatrixId =
      reference("kill_matrix_id", KillMatricesTable, onDelete = ReferenceOption.CASCADE)
  val mutantId = reference("mutant_id", MutantsTable)
  val role = varchar("role", 16)
  val representativeMutantId = integer("representative_mutant_id").nullable()
  val killScenarioIds = array("kill_scenario_ids", IntegerColumnType()).nullable()

  override val primaryKey = PrimaryKey(killMatrixId, mutantId)
}
