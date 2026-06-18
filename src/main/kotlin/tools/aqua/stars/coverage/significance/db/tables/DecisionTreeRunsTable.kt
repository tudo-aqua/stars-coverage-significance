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
 * Table for tracking individual decision tree training runs.
 *
 * Each row represents one execution of the decision tree pipeline with a specific train/test mutant
 * split. The associated per-mutant assignments are stored in [DecisionTreeMutantSplitsTable].
 *
 * @property createdAt Timestamp of when the run was recorded (default: `CURRENT_TIMESTAMP`).
 * @property trainFraction Fraction of unique mutant IDs used for training (0.0–1.0).
 * @property seed Random seed used to shuffle the mutant split.
 * @property nTrainMutants Number of mutants assigned to the training set.
 * @property nTestMutants Number of mutants assigned to the test set.
 * @property logText Full stdout log captured during the training run, stored for later inspection.
 * @property dotSource Graphviz DOT source of the decision tree, stored for later visualization.
 */
object DecisionTreeRunsTable : IntIdTable("decision_tree_runs") {
  val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
  val trainFraction = double("train_fraction")
  val seed = integer("seed")
  val nTrainMutants = integer("n_train_mutants")
  val nTestMutants = integer("n_test_mutants")
  val logText = text("log_text").nullable()
  val dotSource = text("dot_source").nullable()
}
