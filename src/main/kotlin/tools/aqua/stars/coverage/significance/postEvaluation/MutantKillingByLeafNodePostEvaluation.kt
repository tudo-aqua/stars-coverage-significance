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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeLeafAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

/**
 * Post-evaluation that counts, per (leaf_node_id, mutant_id), the number of rows in
 * `metric_failed_monitors` where `next_tick_monitor_g0_Accidents_failed = true`.
 *
 * Call [evaluate] to derive accident leaf node IDs from the database automatically, or supply them
 * explicitly via [evaluate]. The result is written as a pivot CSV to
 * `postEvaluation/mutant_killing_by_leaf_node/mutantKillingByLeafNode.csv`. Render the stacked bar
 * chart with the accompanying Python script.
 */
object MutantKillingByLeafNodePostEvaluation {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "mutant_killing_by_leaf_node")

  private data class LeafMutantCount(val leafNodeId: Int, val mutantId: Int, val count: Long)

  /**
   * Derives accident leaf node IDs from the database, then calls [evaluate] with them.
   *
   * A leaf is considered an accident leaf when at least one row in `metric_failed_monitors` has
   * that `leaf_node_id` and `next_tick_monitor_g0_Accidents_failed = true`.
   */
  fun evaluate() {
    val accidentLeafIds = fetchAccidentLeafIds()
    println("  Accident leaf node IDs: $accidentLeafIds")
    evaluate(accidentLeafIds)
  }

  private fun fetchAccidentLeafIds(): List<Int> = db {
    val latestRunId =
        DecisionTreeRunsTable.selectAll()
            .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(DecisionTreeRunsTable.id) ?: return@db emptyList()

    MetricFailedMonitorsTable.join(
            DecisionTreeLeafAssignmentsTable,
            JoinType.INNER,
            onColumn = MetricFailedMonitorsTable.id,
            otherColumn = DecisionTreeLeafAssignmentsTable.metricFailedMonitorId,
            additionalConstraint = { DecisionTreeLeafAssignmentsTable.runId eq latestRunId })
        .select(DecisionTreeLeafAssignmentsTable.leafNodeId)
        .where { MetricFailedMonitorsTable.nextTickMonitorG0Failed eq true }
        .withDistinct()
        .map { it[DecisionTreeLeafAssignmentsTable.leafNodeId] }
        .sorted()
  }

  /**
   * Queries accident rows for the given leaf nodes and writes the pivot CSV.
   *
   * @param accidentLeafIds Leaf node IDs identified as accident leaves by the classifier (e.g.
   *   `listOf(0, 7, 9, 13, 15)`).
   */
  fun evaluate(accidentLeafIds: List<Int>) {
    require(accidentLeafIds.isNotEmpty()) { "accidentLeafIds must not be empty." }
    println("Starting MutantKillingByLeafNodePostEvaluation.")

    val rows = queryLeafMutantCounts(accidentLeafIds)
    writeCsv(rows, accidentLeafIds)

    println("Finished MutantKillingByLeafNodePostEvaluation.")
  }

  private fun queryLeafMutantCounts(leafIds: List<Int>): List<LeafMutantCount> = db {
    val latestRunId =
        DecisionTreeRunsTable.selectAll()
            .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(DecisionTreeRunsTable.id) ?: return@db emptyList()

    val countExpr = MetricFailedMonitorsTable.id.count()
    MetricFailedMonitorsTable.join(
            DecisionTreeLeafAssignmentsTable,
            JoinType.INNER,
            onColumn = MetricFailedMonitorsTable.id,
            otherColumn = DecisionTreeLeafAssignmentsTable.metricFailedMonitorId,
            additionalConstraint = { DecisionTreeLeafAssignmentsTable.runId eq latestRunId })
        .select(
            DecisionTreeLeafAssignmentsTable.leafNodeId,
            MetricFailedMonitorsTable.mutant,
            countExpr)
        .where {
          DecisionTreeLeafAssignmentsTable.leafNodeId.inList(leafIds) and
              (MetricFailedMonitorsTable.nextTickMonitorG0Failed eq true)
        }
        .groupBy(DecisionTreeLeafAssignmentsTable.leafNodeId, MetricFailedMonitorsTable.mutant)
        .map { row ->
          LeafMutantCount(
              leafNodeId = row[DecisionTreeLeafAssignmentsTable.leafNodeId],
              mutantId = row[MetricFailedMonitorsTable.mutant].value,
              count = row[countExpr],
          )
        }
  }

  private fun writeCsv(rows: List<LeafMutantCount>, leafIds: List<Int>) {
    val sortedLeafIds = leafIds.sorted()
    val mutantIds = rows.map { it.mutantId }.distinct().sorted()

    // pivot: mutant_id → leaf_node_id → count
    val pivot = mutableMapOf<Int, MutableMap<Int, Long>>()
    for (row in rows) {
      pivot.getOrPut(row.mutantId) { mutableMapOf() }[row.leafNodeId] = row.count
    }

    val header = "mutant_id," + sortedLeafIds.joinToString(",") { "leaf_$it" }
    val dataLines =
        mutantIds.map { mutantId ->
          val counts = sortedLeafIds.map { leafId -> pivot[mutantId]?.get(leafId) ?: 0L }
          "$mutantId," + counts.joinToString(",")
        }

    val csvContent = (listOf(header) + dataLines).joinToString("\n")
    val csvPath = BASE_PATH.resolve("mutantKillingByLeafNode.csv")
    Files.createDirectories(csvPath.parent)
    csvPath.writeText(csvContent)
    println("  CSV written to: $csvPath")
  }
}
