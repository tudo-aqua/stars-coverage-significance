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
 * Training and scoring mutants of a tree in [StartScenarioGroupingRunsTable] (like
 * [DecisionTreeMutantSplitsTable] for the tick-level trees).
 *
 * @property runId The grouping run.
 * @property mutantId Id in [MutantsTable].
 * @property trainedOn Whether the tree, its search and the greedy order may use this mutant.
 * @property scoredOn Whether the draw step scores suites on this mutant.
 */
object StartScenarioGroupingMutantSplitsTable : Table("start_scenario_grouping_mutant_splits") {
  val runId =
      reference("run_id", StartScenarioGroupingRunsTable, onDelete = ReferenceOption.CASCADE)
  val mutantId = reference("mutant_id", MutantsTable)
  val trainedOn = bool("trained_on")
  val scoredOn = bool("scored_on")

  override val primaryKey = PrimaryKey(runId, mutantId)
}
