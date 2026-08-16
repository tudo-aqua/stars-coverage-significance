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

package tools.aqua.stars.coverage.significance.db.dataclasses

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Data class representing a row in the `decision_tree_runs` table.
 *
 * @property id Unique identifier of the decision tree run.
 * @property createdAt Timestamp of when the run was recorded.
 * @property trainFraction The requested `--train-fraction` split ratio (0.0–1.0); null for a manual
 *   [manualMutantSelection] run left at the default 1.0, since its train/test split isn't a
 *   fraction the user requested. Use [nTestMutants] to check whether a run has a test set at all,
 *   not this column.
 * @property manualMutantSelection Whether `--mutant-ids`/`--mutant-numbers` restricted this run to
 *   a hand-picked mutant set. Null for older runs recorded before this column existed.
 * @property seed Random seed used to shuffle the mutant split.
 * @property nTrainMutants Number of mutants assigned to the training set.
 * @property nTestMutants Number of mutants assigned to the test set.
 * @property logText Full stdout log captured during the training run, null if not yet persisted.
 * @property dotSource Graphviz DOT source of the decision tree, null if not yet persisted.
 * @property modelText The fitted LightGBM booster serialized via `Booster.model_to_string()`, null
 *   if not yet persisted. Reload via `lgb.Booster(model_str=...)` to label new data without
 *   retraining.
 * @property featureColumns The exact, ordered feature column names this run's booster was trained
 *   on, null if not yet persisted. Required alongside [modelText] since leaf-prediction aligns
 *   feature columns positionally, not by name.
 * @property featEgoManeuver Whether the ego-maneuver feature group was enabled.
 * @property featEgoSpeed Whether the ego-speed feature group was enabled.
 * @property featEgoAccel Whether the ego-accel feature group was enabled.
 * @property featEgoPosition Whether the ego-position feature group was enabled.
 * @property featDistances Whether the distances feature group was enabled.
 * @property featNeighborKinematics Whether the neighbor-kinematics feature group was enabled.
 * @property featTimeGaps Whether the time-gaps feature group was enabled.
 * @property nTrials Number of Optuna trials used during hyperparameter search.
 * @property maxLeavesBound Upper bound for num_leaves in the Optuna search.
 * @property classWeight Class imbalance strategy: `'balanced'` or `'scale-pos-weight'`.
 * @property scalePosWeight Computed n_neg/n_pos ratio used when [classWeight] is
 *   `'scale-pos-weight'`; null when [classWeight] is `'balanced'`.
 * @property hpNumLeaves Best num_leaves found by Optuna.
 * @property hpMaxDepth Best max_depth found by Optuna.
 * @property hpMinChildSamples Best min_child_samples found by Optuna.
 * @property hpMinSplitGain Best min_split_gain found by Optuna.
 * @property tuningRocAuc Training-set ROC-AUC of the best trial.
 * @property learnedNumLeaves Actual number of leaves in the fitted tree; may differ from
 *   [hpNumLeaves] since `num_leaves` is an upper bound for leaf-wise growth, not a guarantee.
 * @property learnedMaxDepth Actual max depth of the fitted tree (root = 0); may differ from
 *   [hpMaxDepth] for the same reason.
 * @property trainAccuracy Accuracy of the fitted tree on the training set.
 * @property testAccuracy Accuracy of the fitted tree on the held-out test set; null when there is
 *   no test set at all ([nTestMutants] is `0`).
 * @property usedMutants Every mutant ID that went into this run (train ∪ test), null if not
 *   recorded (e.g. runs created before this column existed).
 */
data class DecisionTreeRunEntry(
    val id: Int? = null,
    val createdAt: Instant = Instant.now(),
    val trainFraction: Double? = null,
    val manualMutantSelection: Boolean? = null,
    val seed: Int,
    val nTrainMutants: Int,
    val nTestMutants: Int,
    val logText: String? = null,
    val dotSource: String? = null,
    val modelText: String? = null,
    val featureColumns: List<String>? = null,
    val featEgoManeuver: Boolean? = null,
    val featEgoSpeed: Boolean? = null,
    val featEgoAccel: Boolean? = null,
    val featEgoPosition: Boolean? = null,
    val featDistances: Boolean? = null,
    val featNeighborKinematics: Boolean? = null,
    val featTimeGaps: Boolean? = null,
    val nTrials: Int? = null,
    val maxLeavesBound: Int? = null,
    val classWeight: String? = null,
    val scalePosWeight: Double? = null,
    val hpNumLeaves: Int? = null,
    val hpMaxDepth: Int? = null,
    val hpMinChildSamples: Int? = null,
    val hpMinSplitGain: Double? = null,
    val tuningRocAuc: Double? = null,
    val learnedNumLeaves: Int? = null,
    val learnedMaxDepth: Int? = null,
    val trainAccuracy: Double? = null,
    val testAccuracy: Double? = null,
    val usedMutants: List<Int>? = null,
) {
  /**
   * Converts a [DecisionTreeRunEntry] to its serializable [DecisionTreeRunMetadata].
   *
   * @return The corresponding [DecisionTreeRunMetadata].
   * @receiver The [DecisionTreeRunEntry] to convert.
   */
  fun toMetadata(): DecisionTreeRunMetadata =
      DecisionTreeRunMetadata(
          createdAt = createdAt.toString(),
          trainFraction = trainFraction,
          manualMutantSelection = manualMutantSelection,
          seed = seed,
          nTrainMutants = nTrainMutants,
          nTestMutants = nTestMutants,
          logText = logText,
          dotSource = dotSource,
          modelText = modelText,
          featureColumns = featureColumns,
          featEgoManeuver = featEgoManeuver,
          featEgoSpeed = featEgoSpeed,
          featEgoAccel = featEgoAccel,
          featEgoPosition = featEgoPosition,
          featDistances = featDistances,
          featNeighborKinematics = featNeighborKinematics,
          featTimeGaps = featTimeGaps,
          nTrials = nTrials,
          maxLeavesBound = maxLeavesBound,
          classWeight = classWeight,
          scalePosWeight = scalePosWeight,
          hpNumLeaves = hpNumLeaves,
          hpMaxDepth = hpMaxDepth,
          hpMinChildSamples = hpMinChildSamples,
          hpMinSplitGain = hpMinSplitGain,
          tuningRocAuc = tuningRocAuc,
          learnedNumLeaves = learnedNumLeaves,
          learnedMaxDepth = learnedMaxDepth,
          trainAccuracy = trainAccuracy,
          testAccuracy = testAccuracy,
          usedMutants = usedMutants,
      )
}

/**
 * Serializable mirror of [DecisionTreeRunEntry]'s columns from the `decision_tree_runs` table.
 *
 * @property createdAt ISO-8601 timestamp of when the run was recorded.
 * @property trainFraction The requested `--train-fraction` split ratio (0.0–1.0); null for a manual
 *   [manualMutantSelection] run left at the default 1.0. Use [nTestMutants] to check whether a run
 *   has a test set at all, not this column.
 * @property manualMutantSelection Whether `--mutant-ids`/`--mutant-numbers` restricted this run to
 *   a hand-picked mutant set. Null for older runs recorded before this column existed.
 * @property seed Random seed used to shuffle the mutant split.
 * @property nTrainMutants Number of mutants assigned to the training set.
 * @property nTestMutants Number of mutants assigned to the test set.
 * @property logText Full stdout log captured during the training run, null if not persisted.
 * @property dotSource Graphviz DOT source of the decision tree, null if not persisted.
 * @property modelText The fitted LightGBM booster serialized via `Booster.model_to_string()`, null
 *   if not persisted.
 * @property featureColumns The exact, ordered feature column names this run's booster was trained
 *   on, null if not persisted.
 * @property featEgoManeuver Whether the ego-maneuver feature group was enabled.
 * @property featEgoSpeed Whether the ego-speed feature group was enabled.
 * @property featEgoAccel Whether the ego-accel feature group was enabled.
 * @property featEgoPosition Whether the ego-position feature group was enabled.
 * @property featDistances Whether the distances feature group was enabled.
 * @property featNeighborKinematics Whether the neighbor-kinematics feature group was enabled.
 * @property featTimeGaps Whether the time-gaps feature group was enabled.
 * @property nTrials Number of Optuna trials used during hyperparameter search.
 * @property maxLeavesBound Upper bound for num_leaves in the Optuna search.
 * @property classWeight Class imbalance strategy: `'balanced'` or `'scale-pos-weight'`.
 * @property scalePosWeight Computed n_neg/n_pos ratio used when [classWeight] is
 *   `'scale-pos-weight'`; null when [classWeight] is `'balanced'`.
 * @property hpNumLeaves Best num_leaves found by Optuna.
 * @property hpMaxDepth Best max_depth found by Optuna.
 * @property hpMinChildSamples Best min_child_samples found by Optuna.
 * @property hpMinSplitGain Best min_split_gain found by Optuna.
 * @property tuningRocAuc Training-set ROC-AUC of the best trial.
 * @property learnedNumLeaves Actual number of leaves in the fitted tree; may differ from
 *   [hpNumLeaves] since `num_leaves` is an upper bound for leaf-wise growth, not a guarantee.
 * @property learnedMaxDepth Actual max depth of the fitted tree (root = 0); may differ from
 *   [hpMaxDepth] for the same reason.
 * @property trainAccuracy Accuracy of the fitted tree on the training set.
 * @property testAccuracy Accuracy of the fitted tree on the held-out test set; null when there is
 *   no test set at all ([nTestMutants] is `0`).
 * @property usedMutants Every mutant ID that went into this run (train ∪ test), null if not
 *   recorded (e.g. runs created before this column existed).
 */
@Serializable
data class DecisionTreeRunMetadata(
    val createdAt: String,
    val trainFraction: Double?,
    val manualMutantSelection: Boolean?,
    val seed: Int,
    val nTrainMutants: Int,
    val nTestMutants: Int,
    val logText: String?,
    val dotSource: String?,
    val modelText: String?,
    val featureColumns: List<String>?,
    val featEgoManeuver: Boolean?,
    val featEgoSpeed: Boolean?,
    val featEgoAccel: Boolean?,
    val featEgoPosition: Boolean?,
    val featDistances: Boolean?,
    val featNeighborKinematics: Boolean?,
    val featTimeGaps: Boolean?,
    val nTrials: Int?,
    val maxLeavesBound: Int?,
    val classWeight: String?,
    val scalePosWeight: Double?,
    val hpNumLeaves: Int?,
    val hpMaxDepth: Int?,
    val hpMinChildSamples: Int?,
    val hpMinSplitGain: Double?,
    val tuningRocAuc: Double?,
    val learnedNumLeaves: Int?,
    val learnedMaxDepth: Int?,
    val trainAccuracy: Double?,
    val testAccuracy: Double?,
    val usedMutants: List<Int>?,
)
