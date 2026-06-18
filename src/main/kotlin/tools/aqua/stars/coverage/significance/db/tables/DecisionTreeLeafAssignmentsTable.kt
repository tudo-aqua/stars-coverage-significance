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

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Table for storing the leaf node assignment of each [MetricFailedMonitorsTable] row for a given
 * decision tree run.
 *
 * Replacing the `leaf_node_id` column that previously lived on [MetricFailedMonitorsTable], this
 * table allows multiple decision tree runs to coexist in the database without overwriting each
 * other's annotations.
 *
 * The composite primary key `(run_id, metric_failed_monitor_id)` ensures every row in
 * [MetricFailedMonitorsTable] is assigned at most one leaf node per run.
 *
 * @property runId Reference to the [DecisionTreeRunsTable] entry this assignment belongs to.
 * @property metricFailedMonitorId Reference to the annotated [MetricFailedMonitorsTable] row.
 * @property leafNodeId Leaf node index assigned by the decision tree classifier for this row.
 */
object DecisionTreeLeafAssignmentsTable : Table("decision_tree_leaf_assignments") {
  val runId = reference("run_id", DecisionTreeRunsTable, onDelete = ReferenceOption.CASCADE)
  val metricFailedMonitorId =
      reference(
          "metric_failed_monitor_id", MetricFailedMonitorsTable, onDelete = ReferenceOption.CASCADE)
  val leafNodeId = integer("leaf_node_id")

  override val primaryKey = PrimaryKey(runId, metricFailedMonitorId)
}
