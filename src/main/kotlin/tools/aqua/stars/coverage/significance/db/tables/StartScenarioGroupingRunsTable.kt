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
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One grouping of the starting scenarios per row (steps B and C of `docs/test-bed-plan.md`): a
 * learned tree (`kind = tree`, written by `scripts/decision_tree_start_scenarios.py`) or a TSC
 * (`kind = tsc`, written by
 * [tools.aqua.stars.coverage.significance.postEvaluation.scenario.StartScenarioTscGroupings]). The
 * group of each scenario is in [StartScenarioGroupAssignmentsTable], so the draw step treats trees
 * and TSCs alike. Same pattern as the tick-level [DecisionTreeRunsTable], kept apart from it so the
 * old pipeline does not pick these rows up.
 *
 * Tree-only columns are `null` for TSC rows.
 *
 * @property createdAt When the row was written.
 * @property kind `tree` or `tsc`.
 * @property tscId The TSC of a `tsc` row; its groups are [TSCInstancesTable] ids.
 * @property killMatrixId The kill matrix a tree was trained on (gives oracle and thresholds).
 * @property condition Experiment condition, e.g. `full` or `manual`.
 * @property featureSet `with_ego_lane` or `without_ego_lane`.
 * @property featureColumns Feature columns in the order the model expects them.
 * @property manualMutantSelection Whether `--mutant-ids` / `--mutant-numbers` restricted the run.
 * @property trainFraction Share of the selected mutants used for training.
 * @property leafBound Upper bound on the number of leaves in the search.
 * @property seed Seed of the search and the mutant split.
 * @property nTrials Optuna trials.
 * @property nFolds Inner folds over the training mutants.
 * @property hpNumLeaves Best `num_leaves`.
 * @property hpMaxDepth Best `max_depth`.
 * @property hpMinScenariosPerLeaf Best minimum number of scenarios per leaf.
 * @property hpMinSplitGain Best `min_split_gain`.
 * @property cvRocAuc Mean weighted ROC-AUC over the inner folds of the best trial.
 * @property learnedNumLeaves Leaves of the final tree.
 * @property learnedMaxDepth Depth of the final tree.
 * @property nGroups Distinct groups among the assigned scenarios.
 * @property modelText LightGBM `model_to_string()` of the final tree.
 * @property logText Run log.
 * @property gitCommit Commit the run was made from.
 * @property secondsSearch Wall-clock time of the hyperparameter search.
 * @property secondsRefit Wall-clock time of the final fit.
 * @property secondsLabelling Wall-clock time of assigning all scenarios.
 */
object StartScenarioGroupingRunsTable : IntIdTable("start_scenario_grouping_runs") {
  val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
  val kind = varchar("kind", 8)
  val tscId = reference("tsc_id", TSCsTable).nullable()
  val killMatrixId = reference("kill_matrix_id", KillMatricesTable).nullable()
  val condition = text("condition").nullable()
  val featureSet = varchar("feature_set", 32).nullable()
  val featureColumns = array("feature_columns", TextColumnType()).nullable()
  val manualMutantSelection = bool("manual_mutant_selection").nullable()
  val trainFraction = double("train_fraction").nullable()
  val leafBound = integer("leaf_bound").nullable()
  val seed = integer("seed").nullable()
  val nTrials = integer("n_trials").nullable()
  val nFolds = integer("n_folds").nullable()
  val hpNumLeaves = integer("hp_num_leaves").nullable()
  val hpMaxDepth = integer("hp_max_depth").nullable()
  val hpMinScenariosPerLeaf = integer("hp_min_scenarios_per_leaf").nullable()
  val hpMinSplitGain = double("hp_min_split_gain").nullable()
  val cvRocAuc = double("cv_roc_auc").nullable()
  val learnedNumLeaves = integer("learned_num_leaves").nullable()
  val learnedMaxDepth = integer("learned_max_depth").nullable()
  val nGroups = integer("n_groups")
  val modelText = text("model_text").nullable()
  val logText = text("log_text").nullable()
  val gitCommit = varchar("git_commit", 64).nullable()
  val secondsSearch = double("seconds_search").nullable()
  val secondsRefit = double("seconds_refit").nullable()
  val secondsLabelling = double("seconds_labelling").nullable()
}
