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
 *
 * Feature group flags (all default true in the script):
 * @property featEgoManeuver Whether the ego-maneuver feature group was enabled.
 * @property featEgoSpeed Whether the ego-speed feature group was enabled.
 * @property featEgoAccel Whether the ego-accel feature group was enabled.
 * @property featEgoPosition Whether the ego-position feature group was enabled.
 * @property featDistances Whether the distances feature group was enabled.
 * @property featNeighborKinematics Whether the neighbor-kinematics feature group was enabled.
 * @property featTimeGaps Whether the time-gaps feature group was enabled.
 *
 * Tuning configuration:
 * @property nTrials Number of Optuna trials used during hyperparameter search.
 * @property maxLeavesBound Upper bound for num_leaves in the Optuna search.
 * @property classWeight Class imbalance strategy: `'balanced'` or `'scale-pos-weight'`.
 * @property scalePosWeight Computed n_neg/n_pos ratio used when [classWeight] is
 *   `'scale-pos-weight'`; null when [classWeight] is `'balanced'`.
 *
 * Best hyperparameters selected by Optuna:
 * @property hpNumLeaves Best num_leaves found.
 * @property hpMaxDepth Best max_depth found.
 * @property hpMinChildSamples Best min_child_samples found.
 * @property hpMinSplitGain Best min_split_gain found.
 * @property tuningRocAuc Training-set ROC-AUC of the best trial.
 */
object DecisionTreeRunsTable : IntIdTable("decision_tree_runs") {
  val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
  val trainFraction = double("train_fraction")
  val seed = integer("seed")
  val nTrainMutants = integer("n_train_mutants")
  val nTestMutants = integer("n_test_mutants")
  val logText = text("log_text").nullable()
  val dotSource = text("dot_source").nullable()

  // Feature group flags
  val featEgoManeuver = bool("feat_ego_maneuver").nullable()
  val featEgoSpeed = bool("feat_ego_speed").nullable()
  val featEgoAccel = bool("feat_ego_accel").nullable()
  val featEgoPosition = bool("feat_ego_position").nullable()
  val featDistances = bool("feat_distances").nullable()
  val featNeighborKinematics = bool("feat_neighbor_kinematics").nullable()
  val featTimeGaps = bool("feat_time_gaps").nullable()

  // Tuning configuration
  val nTrials = integer("n_trials").nullable()
  val maxLeavesBound = integer("max_leaves_bound").nullable()
  val classWeight = text("class_weight").nullable()
  val scalePosWeight = double("scale_pos_weight").nullable()

  // Best hyperparameters from Optuna
  val hpNumLeaves = integer("hp_num_leaves").nullable()
  val hpMaxDepth = integer("hp_max_depth").nullable()
  val hpMinChildSamples = integer("hp_min_child_samples").nullable()
  val hpMinSplitGain = double("hp_min_split_gain").nullable()
  val tuningRocAuc = double("tuning_roc_auc").nullable()
}
