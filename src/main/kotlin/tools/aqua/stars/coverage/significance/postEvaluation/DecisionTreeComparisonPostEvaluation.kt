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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.dataclasses.DecisionTreeRunMetadata
import tools.aqua.stars.coverage.significance.db.repositories.DecisionTreeRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.tables.DtMonitorFailuresCombinationView
import tools.aqua.stars.coverage.significance.db.tables.DtMonitorFailuresCombinationViewRow
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DecisionTreeLeafId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantId
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration

object DecisionTreeComparisonPostEvaluation {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "decision_tree_comparison")

  fun evaluate() {
    println("Starting DecisionTreeComparisonPostEvaluation.")

    val decisionTreeRuns = DecisionTreeRunsRepository.getAll()
    val decisionTreeRunIds = decisionTreeRuns.mapNotNull { it.id }
    println("Found ${decisionTreeRunIds.size} decision tree runs.")

    val mutantIds = MutantsRepository.getAllIds()
    println("Found ${mutantIds.size} mutants.")

    val totalTicks = MetricFailedMonitorsRepository.count()
    println("Found $totalTicks total ticks.")

    val failedTicks = MetricFailedMonitorsRepository.countFailures()
    println("Found $failedTicks failed ticks.")

    val decisionTreeRunsById = decisionTreeRuns.associateBy { it.id }

    val consoleProgress = ConsoleProgress(decisionTreeRunIds.size, "Evaluating Decision Tree Runs")
    val runsById =
        decisionTreeRunIds.associateWith { runId ->
          val decisionTreeMonitorFailures = DtMonitorFailuresCombinationView.getForRunId(runId)
          val bucketInformation = getBucketSizesForEachLeafNode(decisionTreeMonitorFailures)
          consoleProgress.step()
          DecisionTreeRunExport(
              runInfo = decisionTreeRunsById.getValue(runId).toMetadata(),
              buckets = bucketInformation)
        }

    writeJson(
        DecisionTreeComparisonExport(
            decisionTreeRunIds = decisionTreeRunIds,
            mutantIds = mutantIds,
            totalTicks = totalTicks,
            failedTicks = failedTicks,
            runsById = runsById))

    println("Finished DecisionTreeComparisonPostEvaluation.")
  }

  /**
   * Writes [export] as a single JSON file to
   * `postEvaluation/decision_tree_comparison/decision_tree_comparison.json`.
   *
   * @param export Combined metadata and per-run bucket information to serialize.
   */
  private fun writeJson(export: DecisionTreeComparisonExport) {
    val jsonContent = jsonConfiguration.encodeToString(export)
    val jsonPath = BASE_PATH.resolve("decision_tree_comparison.json")
    Files.createDirectories(jsonPath.parent)
    jsonPath.writeText(jsonContent)
    println("  JSON written to: $jsonPath")
  }

  private fun getBucketSizesForEachLeafNode(
      decisionTreeMonitorFailures: List<DtMonitorFailuresCombinationViewRow>
  ): List<DecisionTreeBucketInformation> {
    val monitorFailuresMappedByLeafNodeId = decisionTreeMonitorFailures.groupBy { it.leafNodeId }
    return monitorFailuresMappedByLeafNodeId.map { (leafId, rows) ->
      val totalTicks = rows.size
      val failingTicks = rows.filter { it.nextTickMonitorG0AccidentFailed }
      val passingTicks = rows.count { !it.nextTickMonitorG0AccidentFailed }
      val mutantKillingAmount =
          failingTicks.groupBy { it.mutantId }.mapValues { (_, rows) -> rows.size.toLong() }

      DecisionTreeBucketInformation(
          leafId = leafId,
          totalTicks = totalTicks.toLong(),
          failingTicks = failingTicks.size.toLong(),
          passingTicks = passingTicks.toLong(),
          mutantKillingAmount = mutantKillingAmount)
    }
  }

  @Serializable
  private data class DecisionTreeBucketInformation(
      val leafId: DecisionTreeLeafId,
      val totalTicks: Long,
      val failingTicks: Long,
      val passingTicks: Long,
      val mutantKillingAmount: Map<MutantId, Long>
  )

  /**
   * Per-run export layer combining the run's [DecisionTreeRunMetadata] with its bucket information.
   *
   * @property runInfo Metadata of this run from the `decision_tree_runs` table.
   * @property buckets Per-leaf bucket information for this run.
   */
  @Serializable
  private data class DecisionTreeRunExport(
      val runInfo: DecisionTreeRunMetadata,
      val buckets: List<DecisionTreeBucketInformation>
  )

  /**
   * Combined export payload written as a single JSON file.
   *
   * @property decisionTreeRunIds IDs of all decision tree runs that were compared.
   * @property mutantIds IDs of all distinct mutants in the database.
   * @property totalTicks Total number of ticks recorded in `metric_failed_monitors`.
   * @property failedTicks Number of ticks with `monitor_g0_failed = true`.
   * @property runsById Metadata and bucket information for each decision tree run, keyed by run ID.
   */
  @Serializable
  private data class DecisionTreeComparisonExport(
      val decisionTreeRunIds: List<Int>,
      val mutantIds: List<MutantId>,
      val totalTicks: Long,
      val failedTicks: Long,
      val runsById: Map<Int, DecisionTreeRunExport>
  )
}
