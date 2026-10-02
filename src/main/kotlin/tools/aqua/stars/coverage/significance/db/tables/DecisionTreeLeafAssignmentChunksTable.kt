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
import org.jetbrains.exposed.sql.ShortColumnType
import org.jetbrains.exposed.sql.Table

/**
 * Number of consecutive [MetricFailedMonitorsTable] ids covered by one
 * [DecisionTreeLeafAssignmentChunksTable] row. Must match `LEAF_ASSIGNMENT_CHUNK_SIZE` in
 * `scripts/decision_tree_g0.py` and `scripts/label_new_ticks.py`, which write the chunks.
 */
const val LEAF_ASSIGNMENT_CHUNK_SIZE = 10_000

/**
 * Leaf node assignments of every [MetricFailedMonitorsTable] row for each decision tree run, stored
 * compactly: one row per run and block of [LEAF_ASSIGNMENT_CHUNK_SIZE] consecutive ids, holding the
 * leaf of id `firstMetricFailedMonitorId + i` at array index `i` (`NULL` where that id has no
 * assignment, e.g. a gap in the id sequence).
 *
 * One row per (run, tick) — the former layout of `decision_tree_leaf_assignments` — cost ~100 bytes
 * per tick with its indexes, i.e. ~130 GB per run at ~1.3 billion ticks. Here a tick costs 2 bytes
 * before PostgreSQL's automatic compression of the (large, TOASTed) arrays. Read the assignments
 * through [DecisionTreeLeafAssignmentsTable], the view that expands the chunks back into one row
 * per tick.
 *
 * Unlike the former layout, there is no foreign key to [MetricFailedMonitorsTable]: deleting ticks
 * leaves their assignments in place. Ids are never reused, so they never attach to other ticks, and
 * every analysis joins with [MetricFailedMonitorsTable], which drops them.
 *
 * @property runId Decision tree run the assignments belong to.
 * @property firstMetricFailedMonitorId First [MetricFailedMonitorsTable] id covered by this chunk;
 *   a multiple of [LEAF_ASSIGNMENT_CHUNK_SIZE].
 * @property leafNodeIds [LEAF_ASSIGNMENT_CHUNK_SIZE] leaf node ids, `NULL` where unassigned.
 */
object DecisionTreeLeafAssignmentChunksTable : Table("decision_tree_leaf_assignment_chunks") {
  val runId = reference("run_id", DecisionTreeRunsTable, onDelete = ReferenceOption.CASCADE)
  val firstMetricFailedMonitorId = long("first_metric_failed_monitor_id")
  val leafNodeIds = array<Short?>("leaf_node_ids", ShortColumnType())

  override val primaryKey = PrimaryKey(runId, firstMetricFailedMonitorId)
}
