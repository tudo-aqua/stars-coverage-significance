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

/**
 * Exposed mapping for the `decision_tree_leaf_assignments` view: the leaf node assignment of each
 * [MetricFailedMonitorsTable] row for a given decision tree run, one row per (run, tick).
 *
 * The data lives compactly in [DecisionTreeLeafAssignmentChunksTable]; this view expands it back
 * into the row-per-tick shape that queries join against (`metric_failed_monitors.id =
 * decision_tree_leaf_assignments.metric_failed_monitor_id AND run_id = ...`). Always filter by
 * [runId]: the view then only expands that run's chunks.
 *
 * This is a view, not a table — it is created by
 * [tools.aqua.stars.coverage.significance.db.DbBootstrap.createSchema] via [CREATE_VIEW_SQL] and
 * must not be passed to Exposed's schema creation. The `reference` columns only give joins the same
 * column types as the referenced ids; no foreign keys exist.
 *
 * @property runId Reference to the [DecisionTreeRunsTable] entry this assignment belongs to.
 * @property metricFailedMonitorId Reference to the annotated [MetricFailedMonitorsTable] row.
 * @property leafNodeId Leaf node index assigned by the decision tree classifier for this row.
 */
object DecisionTreeLeafAssignmentsTable : Table("decision_tree_leaf_assignments") {
  val runId = reference("run_id", DecisionTreeRunsTable)
  val metricFailedMonitorId = reference("metric_failed_monitor_id", MetricFailedMonitorsTable)
  val leafNodeId = integer("leaf_node_id")

  /** Query defining the view. `scripts/decision_tree_g0.py` keeps a verbatim copy. */
  const val VIEW_QUERY =
      """SELECT c.run_id,
       c.first_metric_failed_monitor_id + a.idx - 1 AS metric_failed_monitor_id,
       a.leaf_node_id::integer                      AS leaf_node_id
FROM decision_tree_leaf_assignment_chunks c
     CROSS JOIN LATERAL unnest(c.leaf_node_ids) WITH ORDINALITY AS a(leaf_node_id, idx)
WHERE a.leaf_node_id IS NOT NULL"""

  /**
   * Creates the view unless it exists. Every evaluation worker runs this at startup, often many at
   * once; a concurrent creation by another worker is therefore expected and ignored.
   */
  const val CREATE_VIEW_SQL =
      """DO $$
BEGIN
  CREATE VIEW decision_tree_leaf_assignments AS $VIEW_QUERY;
EXCEPTION WHEN duplicate_table OR unique_violation THEN NULL;
END $$"""
}
