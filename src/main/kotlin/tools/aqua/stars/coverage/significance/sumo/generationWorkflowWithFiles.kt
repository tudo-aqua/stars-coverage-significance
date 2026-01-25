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

package tools.aqua.stars.coverage.significance.sumo

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.Path
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.core.metrics.evaluation.TickCountMetric
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.COLLISION_DIR
import tools.aqua.stars.coverage.significance.EXPORT_DIR
import tools.aqua.stars.coverage.significance.GRID_TRAFFIC_DIR
import tools.aqua.stars.coverage.significance.SCENARIO_DIR
import tools.aqua.stars.coverage.significance.TAKE_ONLY_TICKS_AT_X_MILLIS
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.generateGridTrafficScenarios
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.coverage.significance.parallelism
import tools.aqua.stars.coverage.significance.staticTsc
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress
import tools.aqua.stars.coverage.significance.utils.baseKey
import tools.aqua.stars.coverage.significance.utils.buckets
import tools.aqua.stars.coverage.significance.utils.listSortedFiles
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.data.sumo.xml.SumoImporter

/**
 * Runs the full generation, simulation, import, and evaluation workflow using the filesystem for
 * routes.
 */
fun generationWorkflowWithFiles() {
  DbBootstrap.connect()

  val evaluationRunEntryId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())

  generateGridTrafficScenarios(seed = 2)

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
  val tscEntryId = TSCsRepository.upsertAndGetId(entry = staticTsc.toTSCEntry())

  val bucketCount = minOf(parallelism, scenarioFiles.size.coerceAtLeast(1))
  val buckets = scenarioFiles.buckets(bucketCount)

  println(
      "Split ${scenarioFiles.size} scenarios into ${buckets.size} buckets (parallelism=${parallelism})")

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

                val tickSequence =
                    SumoImporter.loadTicks(
                        scenarioFiles = bucketScenarioFiles,
                        exportFiles = bucketExports,
                        collisionsFiles = bucketCollisions,
                        bufferSize = BUFFER_SIZE,
                        netFilePath = Path("${GRID_TRAFFIC_DIR}/grid_highway.net.xml"),
                        vehicleTypesAdditionalFilePath = Path("${GRID_TRAFFIC_DIR}/vTypes.add.xml"),
                        takeOnlyTicksAtXMillis = TAKE_ONLY_TICKS_AT_X_MILLIS,
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
                    MinTicksPerTickSequenceHook(BUFFER_SIZE))
                tscEvaluation.registerMetricProviders(
                    InvalidTSCInstancesPerTSCMetric(),
                    StartingValidTSCInstancesPerTSCMetric(),
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
