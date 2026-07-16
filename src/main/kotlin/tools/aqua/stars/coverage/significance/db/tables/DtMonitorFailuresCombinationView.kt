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
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.StartingScenarioId
import tools.aqua.stars.coverage.significance.utils.boolToInt

/**
 * Per-leaf tick totals for a decision tree run, aggregated in SQL.
 *
 * @property leafNodeId Leaf node index.
 * @property totalTicks Total number of ticks assigned to this leaf.
 * @property failingTicks Number of ticks in this leaf where
 *   [DtMonitorFailuresCombinationView.nextTickMonitorG0AccidentFailed] is `true`.
 * @property passingTicks Number of ticks in this leaf where the flag is `false`.
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
 * One row from [DtMonitorFailuresCombinationViewRow].
 *
 * @property decisionTreeRunId ID of the decision tree run.
 * @property metricFailedMonitorId ID of the metric failed monitor.
 * @property tick Simulation tick index.
 * @property mutantId ID of the mutant evaluated in this row.
 * @property scenarioConfigId ID of the scenario configuration.
 * @property leafNodeId Decision-tree leaf node assigned by the run referenced in the view's WHERE
 *   clause.
 */
data class DtMonitorFailuresCombinationViewRow(
    val decisionTreeRunId: Int,
    val metricFailedMonitorId: Int,
    val tick: Long,
    val mutantId: MutantId,
    val scenarioConfigId: StartingScenarioId,
    val nextTickMonitorG0AccidentFailed: Boolean,
    val leafNodeId: Int,
)

/**
 * Exposed mapping for the `dt_monitor-failures_combination` PostgreSQL materialized view.
 *
 * The view joins [MetricFailedMonitorsTable] and [DecisionTreeLeafAssignmentsTable] to expose
 * per-tick rows enriched with the leaf node assignment and the next tick G0-violation flag for the
 * corresponding tick.
 *
 * The materialized view DDL is:
 * ```sql
 * SELECT decision_tree_leaf_assignments.run_id AS decision_tree_run_id,
 * metric_failed_monitors.id             AS metric_failed_monitors_id,
 * metric_failed_monitors.tick,
 * metric_failed_monitors.mutant_id,
 * metric_failed_monitors.scenario_config_id,
 * metric_failed_monitors."next_tick_monitor_g0_Accidents_failed",
 * decision_tree_leaf_assignments.leaf_node_id
 * FROM metric_failed_monitors
 * JOIN decision_tree_leaf_assignments
 * ON metric_failed_monitors.id = decision_tree_leaf_assignments.metric_failed_monitor_id
 * ```
 *
 * The view is created (or replaced) at schema bootstrap time by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.createSchema].
 */
object DtMonitorFailuresCombinationView : Table("dt_monitor_failures_combination") {

  /** Foreign key to [DecisionTreeRunsTable]. */
  val decisionTreeRunId = integer("decision_tree_run_id")

  /** Primary key of the underlying [MetricFailedMonitorsTable] row. */
  val metricFailedMonitorsId = integer("metric_failed_monitors_id")

  /** Simulation tick index. */
  val tick = long("tick")

  /** Foreign key to [MutantsTable]: the mutant evaluated in this row. */
  val mutantId = integer("mutant_id")

  /** Foreign key to [ScenarioStartingConfigurationTable]: the scenario configuration. */
  val scenarioConfigId = integer("scenario_config_id")

  /** Decision-tree leaf node assigned by the run referenced in the view's WHERE clause. */
  val leafNodeId = integer("leaf_node_id")

  /** `true` if the G0 accident monitor fires in the next tick. */
  val nextTickMonitorG0AccidentFailed = bool("next_tick_monitor_g0_Accidents_failed")

  /** Returns all rows from the view. */
  fun getAll(): List<DtMonitorFailuresCombinationViewRow> = transaction {
    selectAll().map { row ->
      DtMonitorFailuresCombinationViewRow(
          decisionTreeRunId = row[decisionTreeRunId],
          metricFailedMonitorId = row[metricFailedMonitorsId],
          tick = row[tick],
          mutantId = row[mutantId],
          scenarioConfigId = row[scenarioConfigId],
          leafNodeId = row[leafNodeId],
          nextTickMonitorG0AccidentFailed = row[nextTickMonitorG0AccidentFailed],
      )
    }
  }

  fun getForRunId(runId: Int): List<DtMonitorFailuresCombinationViewRow> = transaction {
    selectAll()
        .where { decisionTreeRunId eq runId }
        .map { row ->
          DtMonitorFailuresCombinationViewRow(
              decisionTreeRunId = row[decisionTreeRunId],
              metricFailedMonitorId = row[metricFailedMonitorsId],
              tick = row[tick],
              mutantId = row[mutantId],
              scenarioConfigId = row[scenarioConfigId],
              leafNodeId = row[leafNodeId],
              nextTickMonitorG0AccidentFailed = row[nextTickMonitorG0AccidentFailed],
          )
        }
  }

  /**
   * Aggregates tick totals per leaf node for [runId], computed entirely in SQL so no per-tick rows
   * are loaded into the JVM.
   *
   * @param runId ID of the decision tree run.
   * @return Per-leaf tick totals.
   */
  fun getLeafBucketTotalsForRunId(runId: Int): List<DtLeafBucketTotals> = transaction {
    val totalTicksExpr = metricFailedMonitorsId.count()
    val failingTicksExpr = boolToInt(nextTickMonitorG0AccidentFailed).sum()

    select(leafNodeId, totalTicksExpr, failingTicksExpr)
        .where { decisionTreeRunId eq runId }
        .groupBy(leafNodeId)
        .map { row ->
          val total = row[totalTicksExpr]
          val failing = (row[failingTicksExpr] ?: 0).toLong()
          DtLeafBucketTotals(
              leafNodeId = row[leafNodeId],
              totalTicks = total,
              failingTicks = failing,
              passingTicks = total - failing)
        }
  }

  /**
   * Aggregates failing-tick counts per (leaf, mutant) pair for [runId], computed entirely in SQL so
   * no per-tick rows are loaded into the JVM. Only mutants that killed at least one tick in a leaf
   * are returned.
   *
   * @param runId ID of the decision tree run.
   * @return Per-(leaf, mutant) failing tick counts.
   */
  fun getLeafMutantFailureCountsForRunId(runId: Int): List<DtLeafMutantFailureCount> = transaction {
    val failingTicksExpr = metricFailedMonitorsId.count()

    select(leafNodeId, mutantId, failingTicksExpr)
        .where { (decisionTreeRunId eq runId) and (nextTickMonitorG0AccidentFailed eq true) }
        .groupBy(leafNodeId, mutantId)
        .map { row ->
          DtLeafMutantFailureCount(
              leafNodeId = row[leafNodeId],
              mutantId = row[mutantId],
              failingTicks = row[failingTicksExpr])
        }
  }
}
