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

package tools.aqua.stars.coverage.significance

import java.nio.file.Files
import java.nio.file.Path
import tools.aqua.stars.core.serialization.tsc.SerializableTSCNode
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.postEvaluation.ScenarioInstancesLongTailDistributionPostEvaluation.writeOrderedCountsCsv
import tools.aqua.stars.coverage.significance.postEvaluation.ScenarioInstancesLongTailDistributionPostEvaluation.writePgfplotsBarChartTex
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.tsc.tsc
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart
import tools.aqua.stars.coverage.significance.workers.startHighwayTrafficAnalysisWorkerProcess

@Suppress("ThrowingExceptionInMain")
/**
 * Main entry point for running highway traffic analysis workers in parallel.
 *
 * @param args Command line arguments.
 */
fun main(args: Array<String>) {
  val bufferProcessors = parseIntArg(args, "--bufferProcessors", 0).coerceAtLeast(0)
  val parallelism = (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
  println(
      "Starting highway traffic analysis with parallelism=$parallelism " +
          "(bufferProcessors=$bufferProcessors).")

  DbBootstrap.connect()

  val evaluationRunId =
      EvaluationRunsRepository.getLatest()?.id
          ?: error("No highway traffic evaluation run found; cannot start workers.")

  val tscEntryId =
      TSCsRepository.getByJson(SerializableTSCNode(tsc().rootNode).getJsonString())?.id
          ?: error("Static TSC not found in database; cannot start workers.")

  val processes: List<NamedProcess> =
      (0 until parallelism).map { idx ->
        NamedProcess(
            name = "highway-worker-$idx",
            process =
                startHighwayTrafficAnalysisWorkerProcess(
                    workerId = "highway-worker-$idx",
                    evaluationRunId = evaluationRunId,
                    tscEntryId = tscEntryId))
      }

  try {
    ProcessGroupRunner.awaitAll(groupLabel = "highway traffic worker", processes = processes)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    processes.forEach { it.killProcessTree() }
    throw e
  }

  createPlots()
}

private fun parseIntArg(args: Array<String>, longName: String, defaultValue: Int): Int {
  args
      .firstOrNull { it.startsWith("$longName=") }
      ?.substringAfter("=")
      ?.toIntOrNull()
      ?.let {
        return it
      }

  args
      .indexOfFirst { it == longName }
      .takeIf { it >= 0 && it + 1 < args.size }
      ?.let { idx -> args[idx + 1].toIntOrNull() }
      ?.let {
        return it
      }

  return defaultValue
}

private fun createPlots() {
  val highwayTrafficAnalysisScenarios = HighwayTrafficScenariosRepository.getAll()

  val groupedByTSCInstance =
      highwayTrafficAnalysisScenarios.groupingBy { it.tscInstanceId }.eachCount()
  val orderedCounts = groupedByTSCInstance.values.sortedDescending()

  val folder = HIGHWAY_TRAFFIC_ANALYSIS_BASE_DIR
  val subfolder = "highway_scenarios_long_tail_distribution"
  val csvFileName = "${subfolder}.csv"
  val texFileName = "${subfolder}.tex"
  val plotName = "${subfolder}.png"

  val csvPath = Path.of(folder, subfolder, csvFileName)
  val texPath = Path.of(folder, subfolder, texFileName)
  val plotPath = Path.of(folder, subfolder)

  Files.createDirectories(csvPath.parent)

  writeOrderedCountsCsv(orderedCounts, csvPath)
  writePgfplotsBarChartTex(csvPath = csvPath, texPath = texPath)

  val plot =
      getPlot(
          "Scenario Instances Count",
          xValues = orderedCounts.mapIndexed { index, _ -> index },
          yValues = orderedCounts)
  checkNotNull(plot) { "Plot could not be created: $subfolder." }
  plotDataAsBarChart(
      plot, fileName = plotName, path = plotPath, title = "Scenario Long Tail Distribution")
}
