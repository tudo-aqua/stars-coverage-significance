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

package tools.aqua.stars.coverage.significance

import org.jetbrains.exposed.dao.id.EntityID
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable
import tools.aqua.stars.coverage.significance.postEvaluation.BaselineNextTickDrawScenariosPostEvaluation

/**
 * @param args Optionally the decision tree run ID to use for leaf assignments
 *   (`decision_tree_runs.id`). If omitted, the latest full run (train_fraction=1.0) is used
 *   instead.
 */
fun main(args: Array<String>) {
  val decisionTreeRunId = args.firstOrNull()?.toInt()?.let { EntityID(it, DecisionTreeRunsTable) }

  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))
  BaselineNextTickDrawScenariosPostEvaluation.evaluateTimeToKill(decisionTreeRunId)
  BaselineNextTickDrawScenariosPostEvaluation.evaluateWithStartingScenario(decisionTreeRunId)
  println("Finished!")
}
