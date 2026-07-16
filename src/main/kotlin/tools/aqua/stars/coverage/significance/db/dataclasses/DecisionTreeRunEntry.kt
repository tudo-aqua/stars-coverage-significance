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

/**
 * Data class representing a row in the `decision_tree_runs` table.
 *
 * @property id Unique identifier of the decision tree run.
 * @property createdAt Timestamp of when the run was recorded.
 * @property trainFraction Fraction of unique mutant IDs used for training (0.0–1.0).
 * @property seed Random seed used to shuffle the mutant split.
 * @property nTrainMutants Number of mutants assigned to the training set.
 * @property nTestMutants Number of mutants assigned to the test set.
 * @property logText Full stdout log captured during the training run, null if not yet persisted.
 * @property dotSource Graphviz DOT source of the decision tree, null if not yet persisted.
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
 */
data class DecisionTreeRunEntry(
    val id: Int? = null,
    val createdAt: Instant = Instant.now(),
    val trainFraction: Double,
    val seed: Int,
    val nTrainMutants: Int,
    val nTestMutants: Int,
    val logText: String? = null,
    val dotSource: String? = null,
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
)
