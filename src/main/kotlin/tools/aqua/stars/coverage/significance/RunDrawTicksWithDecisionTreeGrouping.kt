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
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable
import tools.aqua.stars.coverage.significance.postEvaluation.DrawTicksWithDecisionTreeGroupingPostEvaluation

/**
 * Selects which decision tree run(s) to evaluate via [args]:
 * - No arguments, or `--latest`: the actual latest decision tree run overall (highest ID),
 *   regardless of whether it's a full or split run.
 * - `--latest-full`: the latest *full* run (`train_fraction = 1.0`) - the old meaning of
 *   `--latest`.
 * - `--latest-split`: the latest *split* run (`train_fraction != 1.0`, i.e. a train/test split).
 * - `--all`: every decision tree run currently in `decision_tree_runs`, evaluated one after another
 *   (ascending ID order). [DrawTicksWithDecisionTreeGroupingPostEvaluation.evaluateAll] reloads the
 *   full tick table once per run, so this still multiplies the runtime by the number of runs - just
 *   not by twice that, the way calling `evaluate`/`evaluateTimeToKill` separately used to.
 * - One or more run IDs (`decision_tree_runs.id`), comma- and/or space-separated, e.g. `8`,
 *   `1,2,3`, or `1 2 3` - evaluated one after another in the given order.
 *
 * @param args See above.
 */
fun main(args: Array<String>) {
  // maxPoolSize = 9: buildTickWiseNextTickMonitorViolations loads ticks via 8 concurrent chunk
  // queries (see its KDoc) - the pool needs at least that many connections free, since it manages
  // its own transactions rather than borrowing an ambient one from here.
  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432, maxPoolSize = 9))

  val runIds: List<Int> =
      when {
        args.isEmpty() || args.contains("--latest") ->
            listOfNotNull(DecisionTreeRunsRepository.getLatestRunId()?.value)
        args.contains("--latest-full") ->
            listOfNotNull(DecisionTreeRunsRepository.getLatestFullRunId()?.value)
        args.contains("--latest-split") ->
            listOfNotNull(DecisionTreeRunsRepository.getLatestSplitRunId()?.value)
        args.contains("--all") -> DecisionTreeRunsRepository.getAll().mapNotNull { it.id }
        else ->
            args
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toInt() }
      }

  if (runIds.isEmpty()) {
    error("No matching decision tree run found for args=${args.toList()}.")
  }

  println("Evaluating ${runIds.size} decision tree run(s): $runIds")
  runIds.forEachIndexed { index, runId ->
    println("=== Run ${index + 1} / ${runIds.size}: decision tree run $runId ===")
    evaluateRun(EntityID(runId, DecisionTreeRunsTable))
  }
  println("Finished!")
}

private fun evaluateRun(decisionTreeRunId: EntityID<Int>) {
  val learnedNumLeaves =
      DecisionTreeRunsRepository.getById(decisionTreeRunId.value)?.learnedNumLeaves
  if (learnedNumLeaves != null && learnedNumLeaves <= 1) {
    println(
        "WARNING: Decision tree run ${decisionTreeRunId.value} has only $learnedNumLeaves leaf " +
            "(no split found during training) - every row falls into a single leaf, so leaf-based " +
            "sampling/significance results for this run are degenerate.")
  }
  DrawTicksWithDecisionTreeGroupingPostEvaluation.evaluateAll(decisionTreeRunId)
}
