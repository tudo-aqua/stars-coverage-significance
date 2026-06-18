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
 * Table for tracking which mutants were used for training or testing in each decision tree run.
 *
 * Each row links a mutant ID to a specific [DecisionTreeRunsTable] entry and records whether that
 * mutant was part of the training set. The composite primary key `(run_id, mutant_id)` ensures each
 * mutant appears exactly once per run.
 *
 * @property runId Reference to the [DecisionTreeRunsTable] entry this split belongs to.
 * @property mutantId ID of the mutant from [MutantsTable].
 * @property trainedOn `true` if this mutant was used for training, `false` for test-set mutants.
 */
object DecisionTreeMutantSplitsTable : Table("decision_tree_mutant_splits") {
  val runId = reference("run_id", DecisionTreeRunsTable, onDelete = ReferenceOption.CASCADE)
  val mutantId = integer("mutant_id")
  val trainedOn = bool("trained_on")

  override val primaryKey = PrimaryKey(runId, mutantId)
}
