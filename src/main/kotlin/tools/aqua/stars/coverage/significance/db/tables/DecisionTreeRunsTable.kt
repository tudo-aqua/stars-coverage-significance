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
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for tracking individual decision tree training runs.
 *
 * Each row represents one execution of the decision tree pipeline with a specific train/test mutant
 * split. The associated per-mutant assignments are stored in [DecisionTreeMutantSplitsTable].
 *
 * @property createdAt Timestamp of when the run was recorded (default: `CURRENT_TIMESTAMP`).
 * @property trainFraction The `--train-fraction` value that produced this run's split (0.0–1.0),
 *   when that flag is what determined the split — i.e. every run except a manual
 *   `--mutant-ids`/`--mutant-numbers` selection left at the default `--train-fraction=1.0`. For
 *   that case this is `null`: the resulting train/test split (requested mutants vs. every other
 *   mutant in the data) isn't a fraction the user asked for, just whatever the mutant counts happen
 *   to imply, so backfilling a derived ratio here would misrepresent it as an intentional split
 *   ratio. See [manualMutantSelection] for whether this run used a hand-picked mutant set, and use
 *   [nTestMutants] rather than this column to check whether a run has a test set at all —
 *   `DecisionTreeRunsRepository.getLatestFullRunId()`/`getLatestSplitRunId()` do exactly that.
 * @property manualMutantSelection Whether `--mutant-ids`/`--mutant-numbers` restricted this run to
 *   a hand-picked mutant set, regardless of whether `--train-fraction` also applied a further
 *   random split on top of that set. Null for older runs recorded before this column existed.
 * @property seed Random seed used to shuffle the mutant split.
 * @property nTrainMutants Number of mutants assigned to the training set.
 * @property nTestMutants Number of mutants assigned to the test set.
 * @property logText Full stdout log captured during the training run, stored for later inspection.
 * @property dotSource Graphviz DOT source of the decision tree, stored for later visualization.
 * @property modelText The fitted LightGBM booster, serialized via `Booster.model_to_string()` -
 *   LightGBM's own portable text format (not pickle-based, so it isn't tied to a specific
 *   Python/LightGBM version). Reload via `lgb.Booster(model_str=...)` to label new data with this
 *   exact tree without retraining.
 * @property featureColumns The exact, ordered list of feature column names this run's booster was
 *   trained on. Needed alongside [modelText] to label new data correctly: the raw
 *   `Booster.predict()` used for leaf assignment aligns feature columns *positionally*, not by
 *   name, so replaying the same order the model was trained with is required - reconstructing it
 *   from [featEgoManeuver]/etc. plus whatever `FEATURE_GROUPS` happens to look like at label time
 *   would silently break if that dict is ever reordered or edited after this run was trained.
 *
 * Feature group flags (all default true in the script):
 *
 * @property featEgoManeuver Whether the ego-maneuver feature group was enabled.
 * @property featEgoSpeed Whether the ego-speed feature group was enabled.
 * @property featEgoAccel Whether the ego-accel feature group was enabled.
 * @property featEgoPosition Whether the ego-position feature group was enabled.
 * @property featDistances Whether the distances feature group was enabled.
 * @property featNeighborKinematics Whether the neighbor-kinematics feature group was enabled.
 * @property featTimeGaps Whether the time-gaps feature group was enabled.
 *
 * Tuning configuration:
 *
 * @property nTrials Number of Optuna trials used during hyperparameter search.
 * @property maxLeavesBound Upper bound for num_leaves in the Optuna search.
 * @property classWeight Class imbalance strategy: `'balanced'` or `'scale-pos-weight'`.
 * @property scalePosWeight Computed n_neg/n_pos ratio used when [classWeight] is
 *   `'scale-pos-weight'`; null when [classWeight] is `'balanced'`.
 *
 * Best hyperparameters selected by Optuna:
 *
 * @property hpNumLeaves Best num_leaves found.
 * @property hpMaxDepth Best max_depth found.
 * @property hpMinChildSamples Best min_child_samples found.
 * @property hpMinSplitGain Best min_split_gain found.
 * @property tuningRocAuc Training-set ROC-AUC of the best trial.
 *
 * Actually learned values of the fitted tree (may differ from the tuned hyperparameters above,
 * since `num_leaves`/`max_depth` are upper bounds only — leaf-wise growth can stop early):
 *
 * @property learnedNumLeaves Actual number of leaves in the fitted tree.
 * @property learnedMaxDepth Actual max depth of the fitted tree (root = 0).
 * @property trainAccuracy Accuracy of the fitted tree on the training set.
 * @property testAccuracy Accuracy of the fitted tree evaluated on test-mutant rows only (never rows
 *   it trained on); null when there is no test set, i.e. no random split (`--train-fraction < 1.0`)
 *   and no `--mutant-ids`/`--mutant-numbers` restriction was given.
 * @property usedMutants Every mutant ID that went into this run (train ∪ test) — what
 *   `--mutant-ids`/`--mutant-numbers` restricted the run to, or every mutant in the Parquet file if
 *   neither was given. Mirrors [DecisionTreeMutantSplitsTable] for this run, as a single queryable
 *   column.
 */
object DecisionTreeRunsTable : IntIdTable("decision_tree_runs") {
  val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
  val trainFraction = double("train_fraction").nullable()
  val manualMutantSelection = bool("manual_mutant_selection").nullable()
  val seed = integer("seed")
  val nTrainMutants = integer("n_train_mutants")
  val nTestMutants = integer("n_test_mutants")
  val logText = text("log_text").nullable()
  val dotSource = text("dot_source").nullable()
  val modelText = text("model_text").nullable()
  val featureColumns = array("feature_columns", TextColumnType()).nullable()

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

  // Actually learned values of the fitted tree
  val learnedNumLeaves = integer("learned_num_leaves").nullable()
  val learnedMaxDepth = integer("learned_max_depth").nullable()
  val trainAccuracy = double("train_accuracy").nullable()
  val testAccuracy = double("test_accuracy").nullable()

  val usedMutants = array("used_mutants", IntegerColumnType()).nullable()
}
