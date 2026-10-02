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

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId

/**
 * Per-leaf tick totals for a decision tree run, aggregated in SQL.
 *
 * @property leafNodeId Leaf node index.
 * @property totalTicks Total number of ticks assigned to this leaf.
 * @property failingTicks Number of ticks in this leaf followed by a G0 (accident) violation in the
 *   next tick.
 * @property passingTicks Number of ticks in this leaf that are not.
 */
data class DtLeafBucketTotals(
    val leafNodeId: Int,
    val totalTicks: Long,
    val failingTicks: Long,
    val passingTicks: Long,
)

/**
 * Per-(leaf, mutant) count of failing ticks for a decision tree run, aggregated in SQL. Only
 * mutants that killed at least one tick in the leaf are represented.
 *
 * @property leafNodeId Leaf node index.
 * @property mutantId ID of the mutant that killed the tick(s).
 * @property failingTicks Number of failing ticks caused by [mutantId] within [leafNodeId].
 */
data class DtLeafMutantFailureCount(
    val leafNodeId: Int,
    val mutantId: MutantId,
    val failingTicks: Long,
)

/**
 * Exposed mapping for the `dt_leaf_mutant_tick_counts` PostgreSQL materialized view: for every
 * (decision tree run, leaf, mutant), how many ticks of that mutant fall into the leaf, and how many
 * of those are followed by a G0 (accident) violation in the next tick.
 *
 * Replaces the former `dt_monitor_failures_combination` view, which held one row per (run, tick) —
 * a full copy of the tick ids for every run — although every consumer only needed these counts.
 *
 * The view is created or refreshed by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.buildMaterializedViews].
 */
object DtLeafMutantTickCountsView : Table("dt_leaf_mutant_tick_counts") {

  /** Foreign key to [DecisionTreeRunsTable]. */
  val decisionTreeRunId = integer("decision_tree_run_id")

  /** Decision-tree leaf node. */
  val leafNodeId = integer("leaf_node_id")

  /** Foreign key to [MutantsTable]. */
  val mutantId = integer("mutant_id")

  /** Number of this mutant's ticks in this leaf. */
  val totalTicks = long("total_ticks")

  /** Number of those ticks whose next tick violates G0. */
  val failingTicks = long("failing_ticks")

  /**
   * Aggregates tick totals per leaf node for [runId].
   *
   * @param runId ID of the decision tree run.
   * @return Per-leaf tick totals.
   */
  fun getLeafBucketTotalsForRunId(runId: Int): List<DtLeafBucketTotals> = transaction {
    val totalTicksExpr = totalTicks.sum()
    val failingTicksExpr = failingTicks.sum()

    select(leafNodeId, totalTicksExpr, failingTicksExpr)
        .where { decisionTreeRunId eq runId }
        .groupBy(leafNodeId)
        .map { row ->
          val total = row[totalTicksExpr] ?: 0
          val failing = row[failingTicksExpr] ?: 0
          DtLeafBucketTotals(
              leafNodeId = row[leafNodeId],
              totalTicks = total,
              failingTicks = failing,
              passingTicks = total - failing)
        }
  }

  /**
   * Failing-tick counts per (leaf, mutant) pair for [runId]. Only mutants that killed at least one
   * tick in a leaf are returned.
   *
   * @param runId ID of the decision tree run.
   * @return Per-(leaf, mutant) failing tick counts.
   */
  fun getLeafMutantFailureCountsForRunId(runId: Int): List<DtLeafMutantFailureCount> = transaction {
    select(leafNodeId, mutantId, failingTicks)
        .where { (decisionTreeRunId eq runId) and (failingTicks greater 0L) }
        .map { row ->
          DtLeafMutantFailureCount(
              leafNodeId = row[leafNodeId],
              mutantId = row[mutantId],
              failingTicks = row[failingTicks])
        }
  }
}
