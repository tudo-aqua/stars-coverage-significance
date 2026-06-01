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
import java.util.UUID
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceTransition
import tools.aqua.stars.coverage.significance.tscInstanceTransitions
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.plotDataAsHeatmap

/**
 * Analyses TSC-instance transitions by building a transition automaton and rendering each
 * TscInstances × TscInstances transition table (overall + one per monitor) as a heatmap.
 */
object TSCInstanceTransitionAnalysis {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "tsc_instance_transitions")

  fun evaluate() {
    println("Starting TSCInstanceTransitionAnalysis.")

    val transitions = tscInstanceTransitions
    if (transitions.isEmpty()) {
      println("No transitions found – skipping TSCInstanceTransitionAnalysis.")
      return
    }

    val instanceIds = deriveOrderedInstanceIds(transitions)
    val idToIndex = instanceIds.withIndex().associate { (i, id) -> id to i }
    val labels = instanceIds.mapIndexed { i, _ -> "#${i + 1}" }

    Files.createDirectories(BASE_PATH)

    for (excludeDiagonal in listOf(false, true)) {
      val suffix = if (excludeDiagonal) "_no_diagonal" else ""

      writeCsvAndHeatmap(
          instanceIds = instanceIds,
          idToIndex = idToIndex,
          labels = labels,
          title = "TSC Instance Transitions${if (excludeDiagonal) " (diagonal excluded)" else ""}",
          subtitle = "Cell value = number of ticks where the TSC instance switched from row → column",
          fileName = "transitions_overall$suffix",
          excludeDiagonal = excludeDiagonal,
          values = { transition -> transition.totalCount },
      )

      for (monitor in MonitorViolation.entries) {
        writeCsvAndHeatmap(
            instanceIds = instanceIds,
            idToIndex = idToIndex,
            labels = labels,
            title = "TSC Instance Transitions – ${monitor.name} failed${if (excludeDiagonal) " (diagonal excluded)" else ""}",
            subtitle = "Cell value = transitions from row → column where ${monitor.name} failed at destination",
            fileName = "transitions_${monitor.name}$suffix",
            excludeDiagonal = excludeDiagonal,
            values = { transition -> transition.monitorCounts[monitor] ?: 0L },
        )
      }
    }

    println("Finished TSCInstanceTransitionAnalysis. Output in $BASE_PATH")
  }

  private fun deriveOrderedInstanceIds(
      transitions: List<TSCInstanceTransition>
  ): List<UUID> =
      (transitions.map { it.fromInstanceId } + transitions.map { it.toInstanceId })
          .distinct()
          .sorted()

  private fun writeCsvAndHeatmap(
      instanceIds: List<UUID>,
      idToIndex: Map<UUID, Int>,
      labels: List<String>,
      title: String,
      subtitle: String,
      fileName: String,
      excludeDiagonal: Boolean,
      values: (TSCInstanceTransition) -> Long,
  ) {
    val n = instanceIds.size
    val matrix = Array(n) { LongArray(n) }

    for (t in tscInstanceTransitions) {
      val fi = idToIndex[t.fromInstanceId] ?: continue
      val ti = idToIndex[t.toInstanceId] ?: continue
      if (excludeDiagonal && fi == ti) continue
      matrix[fi][ti] = values(t)
    }

    val csvPath = BASE_PATH.resolve("$fileName.csv")
    csvPath.writeText(buildCsv(labels, matrix))

    plotDataAsHeatmap(
        fromLabels = labels,
        toLabels = labels,
        values = matrix,
        fileName = "$fileName.png",
        path = BASE_PATH,
        title = title,
        subtitle = subtitle,
        xLabel = "To (destination instance)",
        yLabel = "From (source instance)",
    )
  }

  private fun buildCsv(labels: List<String>, matrix: Array<LongArray>): String {
    val header = "from\\to," + labels.joinToString(",")
    val rows = labels.indices.joinToString("\n") { fi ->
      labels[fi] + "," + matrix[fi].joinToString(",")
    }
    return "$header\n$rows\n"
  }
}
