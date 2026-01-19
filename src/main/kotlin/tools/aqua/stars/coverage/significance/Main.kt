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

import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.Path
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.core.metrics.evaluation.TickCountMetric
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.generateGridTrafficScenarios
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.coverage.significance.sumo.cleanGenerationFiles
import tools.aqua.stars.coverage.significance.sumo.runSumoForScenariosParallel
import tools.aqua.stars.data.sumo.SumoImporter

/** Directory paths for grid traffic scenarios. */
const val GRID_TRAFFIC_DIR = "sumo_data/gridTrafficScenarios"
/** Sub-directory for scenario files. */
const val SCENARIO_DIR = "$GRID_TRAFFIC_DIR/scenarios"
/** Sub-directory for exported SUMO files. */
const val EXPORT_DIR = "$GRID_TRAFFIC_DIR/export"
/** Sub-directory for collision files. */
const val COLLISION_DIR = "$GRID_TRAFFIC_DIR/collision"
/** File extension for scenario files. */
const val SCENARIO_FILE_EXTENSION = "rou.xml"
/** File extension for exported SUMO files. */
const val EXPORT_FILE_EXTENSION = "export.xml"
/** File extension for collision files. */
const val COLLISION_FILE_EXTENSION = "collisions.xml"

/** Generation of scenarios and printing of the TikZ code for the first scenario. */
fun main() {
  DbBootstrap.connectAndCreateSchema()
  sleep(2000) // wait for DB to be ready

  val evaluationRunEntry = EvaluationRunsRepository.insert(EvaluationRunEntry())
  val evaluationRunEntryId = evaluationRunEntry.id
  checkNotNull(evaluationRunEntryId) { "Could not insert evaluation run entry." }

  val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

  cleanGenerationFiles()
  generateGridTrafficScenarios(n = 10_000, seed = 2)

  val scenarioFiles = listSortedFiles(SCENARIO_DIR)
  runSumoForScenariosParallel(
      scenarioFiles = scenarioFiles, parallelism = parallelism, writeCfgFiles = false)

  val exportFiles = listSortedFiles(EXPORT_DIR)
  val collisionFiles = listSortedFiles(COLLISION_DIR)

  val consoleProgress = ConsoleProgress(scenarioFiles.size, "Loading scenario files")

  val exportByKey = exportFiles.associateBy { it.baseKey() }
  val collisionByKey = collisionFiles.associateBy { it.baseKey() }

  require(scenarioFiles.isNotEmpty()) { "No scenario files found." }
  require(exportByKey.isNotEmpty()) { "No export files found." }
  require(collisionByKey.isNotEmpty()) { "No collision files found." }

  val staticTsc = staticTsc()
  val tscEntry = TSCsRepository.upsert(entry = staticTsc.toTSCEntry())
  val tscEntryId = tscEntry.id
  checkNotNull(tscEntryId) { "Could not insert TSC entry." }

  val bufferSizeInSeconds = 10.0
  val takeOnlyTicksAtXMillis = 100
  val bufferSize = ((bufferSizeInSeconds * 1000) / takeOnlyTicksAtXMillis).toInt()

  val bucketCount = minOf(parallelism, scenarioFiles.size.coerceAtLeast(1))
  val buckets = scenarioFiles.buckets(bucketCount)

  println(
      "Split ${scenarioFiles.size} scenarios into ${buckets.size} buckets (parallelism=$parallelism)")

  val pool = Executors.newFixedThreadPool(minOf(parallelism, buckets.size))
  try {
    val futures =
        buckets.mapIndexed { _, bucketScenarioFiles ->
          pool.submit(
              Callable {
                val bucketExports =
                    bucketScenarioFiles.map { sf ->
                      exportByKey[sf.baseKey()] ?: error("Missing export for scenario: ${sf.name}")
                    }
                val bucketCollisions =
                    bucketScenarioFiles.map { sf ->
                      collisionByKey[sf.baseKey()]
                          ?: error("Missing collision for scenario: ${sf.name}")
                    }

                val importer = SumoImporter()
                val tickSequence =
                    importer.loadTicks(
                        scenarioFiles = bucketScenarioFiles,
                        exportFiles = bucketExports,
                        collisionsFiles = bucketCollisions,
                        bufferSize = bufferSize,
                        netFilePath = Path("$GRID_TRAFFIC_DIR/grid_highway.net.xml"),
                        vehicleTypesAdditionalFilePath = Path("$GRID_TRAFFIC_DIR/vTypes.add.xml"),
                        takeOnlyTicksAtXMillis = takeOnlyTicksAtXMillis,
                        maxLengthOfScenarioInSeconds = 30.0,
                        consoleProgress = consoleProgress)

                // Important: each bucket gets its own evaluator + metric instances
                val tscEvaluation =
                    TSCEvaluation(
                        staticTsc,
                        writePlots = false, // avoid file-write contention in parallel
                        writePlotDataCSV = false // same
                        )

                tscEvaluation.registerPreTickEvaluationHooks(
                    MinTicksPerTickSequenceHook(bufferSize))
                tscEvaluation.registerMetricProviders(
                    InvalidTSCInstancesPerTSCMetric(),
                    StartingValidTSCInstancesPerTSCMetric(
                        evaluationRunEntryId = evaluationRunEntryId),
                    TickCountMetric(),
                    FirstTSCInstanceChangeMetric(
                        evaluationRunEntryId = evaluationRunEntryId, tscEntryId = tscEntryId))

                tscEvaluation.runEvaluation(tickSequence)
              })
        }

    // Propagate failures
    futures.forEach { it.get() }
  } finally {
    pool.shutdown()
  }
}

private fun listSortedFiles(dir: String): List<File> =
    Path(dir).toFile().listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()

/**
 * Computes a stable key shared across scenario/export/collision files. Adjust suffix stripping here
 * if your file naming differs.
 */
private fun File.baseKey(): String {
  val n = name
  return when {
    n.endsWith(SCENARIO_FILE_EXTENSION) -> n.removeSuffix(SCENARIO_FILE_EXTENSION)
    n.endsWith(EXPORT_FILE_EXTENSION) -> n.removeSuffix(EXPORT_FILE_EXTENSION)
    n.endsWith(COLLISION_FILE_EXTENSION) -> n.removeSuffix(COLLISION_FILE_EXTENSION)
    n.endsWith(".xml") -> n.removeSuffix(".xml")
    else -> n
  }
}
