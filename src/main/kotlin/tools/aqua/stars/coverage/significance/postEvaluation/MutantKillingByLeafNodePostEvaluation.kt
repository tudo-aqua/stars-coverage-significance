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
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.db

/**
 * Post-evaluation that counts, per (leaf_node_id, mutant_id), the number of rows in
 * `metric_failed_monitors` where `next_tick_monitor_g0_Accidents_failed = true`.
 *
 * Call [evaluate] with the leaf node IDs the decision-tree classifier labelled as accident leaves.
 * The result is written as a pivot CSV to
 * `postEvaluation/mutant_killing_by_leaf_node/mutantKillingByLeafNode.csv`. Render the stacked bar
 * chart with the accompanying Python script.
 */
object MutantKillingByLeafNodePostEvaluation {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "mutant_killing_by_leaf_node")

  private data class LeafMutantCount(val leafNodeId: Int, val mutantId: Int, val count: Long)

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
    val leafIdList = leafIds.joinToString(",")
    val sql =
        """
        SELECT "leaf_node_id", "mutant_id", COUNT(*) AS row_count
        FROM metric_failed_monitors
        WHERE "leaf_node_id" IN ($leafIdList)
          AND "next_tick_monitor_g0_Accidents_failed" = true
        GROUP BY "leaf_node_id", "mutant_id"
        ORDER BY "leaf_node_id", "mutant_id"
        """
            .trimIndent()

    TransactionManager.current().exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
      val result = mutableListOf<LeafMutantCount>()
      while (rs.next()) {
        result +=
            LeafMutantCount(
                leafNodeId = rs.getInt("leaf_node_id"),
                mutantId = rs.getInt("mutant_id"),
                count = rs.getLong("row_count"),
            )
      }
      result
    } ?: emptyList()
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
