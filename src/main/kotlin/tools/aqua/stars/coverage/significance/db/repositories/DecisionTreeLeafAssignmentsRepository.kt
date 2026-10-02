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

package tools.aqua.stars.coverage.significance.db.repositories

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.DecisionTreeLeafAssignmentEntry
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeLeafAssignmentChunksTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeLeafAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.LEAF_ASSIGNMENT_CHUNK_SIZE

/** Repository for querying [DecisionTreeLeafAssignmentsTable]. */
object DecisionTreeLeafAssignmentsRepository {

  /**
   * Retrieves all leaf node assignments.
   *
   * @return All [DecisionTreeLeafAssignmentEntry]s.
   */
  fun getAll(): List<DecisionTreeLeafAssignmentEntry> = transaction {
    DecisionTreeLeafAssignmentsTable.selectAll().map { it.toEntry() }
  }

  /**
   * Retrieves all leaf node assignments recorded for the given decision tree run.
   *
   * @param runId Unique identifier of the decision tree run.
   * @return All [DecisionTreeLeafAssignmentEntry]s belonging to [runId].
   */
  fun getForRun(runId: Int): List<DecisionTreeLeafAssignmentEntry> = transaction {
    DecisionTreeLeafAssignmentsTable.selectAll()
        .where { DecisionTreeLeafAssignmentsTable.runId eq runId }
        .map { it.toEntry() }
  }

  /**
   * Retrieves the leaf node assignment for a single metric entry within a decision tree run.
   *
   * @param runId Unique identifier of the decision tree run.
   * @param metricFailedMonitorId Unique identifier of the annotated metric entry.
   * @return The corresponding [DecisionTreeLeafAssignmentEntry], or `null` if not found.
   */
  fun getByKey(runId: Int, metricFailedMonitorId: Long): DecisionTreeLeafAssignmentEntry? =
      transaction {
        // Reads the one chunk holding this id directly: filtering the view by id would expand every
        // chunk of the run first.
        val firstId = metricFailedMonitorId - metricFailedMonitorId % LEAF_ASSIGNMENT_CHUNK_SIZE
        DecisionTreeLeafAssignmentChunksTable.selectAll()
            .where {
              (DecisionTreeLeafAssignmentChunksTable.runId eq runId) and
                  (DecisionTreeLeafAssignmentChunksTable.firstMetricFailedMonitorId eq firstId)
            }
            .singleOrNull()
            ?.get(DecisionTreeLeafAssignmentChunksTable.leafNodeIds)
            ?.getOrNull((metricFailedMonitorId - firstId).toInt())
            ?.let { leafNodeId ->
              DecisionTreeLeafAssignmentEntry(
                  runId = runId,
                  metricFailedMonitorId = metricFailedMonitorId,
                  leafNodeId = leafNodeId.toInt())
            }
      }

  /**
   * Converts a [ResultRow] to a [DecisionTreeLeafAssignmentEntry].
   *
   * @return The corresponding [DecisionTreeLeafAssignmentEntry].
   */
  private fun ResultRow.toEntry(): DecisionTreeLeafAssignmentEntry =
      DecisionTreeLeafAssignmentEntry(
          runId = this[DecisionTreeLeafAssignmentsTable.runId].value,
          metricFailedMonitorId =
              this[DecisionTreeLeafAssignmentsTable.metricFailedMonitorId].value,
          leafNodeId = this[DecisionTreeLeafAssignmentsTable.leafNodeId],
      )
}
