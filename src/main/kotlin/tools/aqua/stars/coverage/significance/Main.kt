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

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.core.metrics.evaluation.TickCountMetric
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.repositories.ChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.seed.ChunkJobSeeder
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.getGridTrafficScenarios
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

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
/** Number of parallel threads to use for experiment runs. */
val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
/** Number of scenarios to generate and evaluate in the main function. */
const val NUMBER_OF_SCENARIOS = 1
/** Seed for scenario generation and evaluation. Keep this constant to get reproducible results. */
const val SEED = 1
/** Size of the buffer (in seconds) to use when importing tick sequences. */
const val BUFFER_SIZE_IN_SECONDS = 10.0
/** When importing tick sequences, only take ticks at every X milliseconds. This reduces memory */
const val TAKE_ONLY_TICKS_AT_X_MILLIS = 100
/** Size of the buffer (in number of ticks) to use when importing tick sequences. */
const val BUFFER_SIZE = ((BUFFER_SIZE_IN_SECONDS * 1000) / TAKE_ONLY_TICKS_AT_X_MILLIS).toInt()

fun main(args: Array<String>) {
  when (args.firstOrNull()) {
    "worker" -> runWorker(args.drop(1))
    else -> runController()
  }
}

private fun runController(debugSingleWorker: Boolean = true) {
  DbBootstrap.connectAndCreateSchema()

  val evaluationRunEntryId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created evaluation run: $evaluationRunEntryId")

  // Database seeding phase
  val scenarios =
      getGridTrafficScenarios(n = NUMBER_OF_SCENARIOS, seed = SEED, insertIntoDatabase = true)
  val mutantIds = MutantsRepository.ensureMutants(1) // TODO correct number of mutants
  ChunkJobSeeder.seedChunks(runId = evaluationRunEntryId, mutantIds = mutantIds, chunkSize = 1000L)

  if (debugSingleWorker) {
    runWorker(listOf("--workerId=debug", "--runId=$evaluationRunEntryId"))
    return
  }

  val processes =
      (0 until parallelism).map { workerIndex ->
        startWorkerProcess(
            workerId = "worker-$workerIndex", evaluationRunId = evaluationRunEntryId.toString())
      }

  var ok = true
  processes.forEachIndexed { i, p ->
    val code = p.waitFor()
    if (code != 0) {
      ok = false
      System.err.println("Worker $i exited with code=$code")
    }
  }
  check(ok) { "At least one worker failed." }
}

private fun runWorker(args: List<String>) {
  val workerId = args.first { it.startsWith("--workerId=") }.substringAfter("=")
  val runId = args.first { it.startsWith("--runId=") }.substringAfter("=")

  DbBootstrap.connectAndCreateSchema() // safe if it is createMissingTables...
  println("[$workerId] started (runId=$runId)")

  val staticTsc = staticTsc()
  val tscEntryId = TSCsRepository.upsertAndGetId(entry = staticTsc.toTSCEntry())

  // Create per-worker evaluator + metrics once (as you already do in the bucket callable)
  val tscEvaluation =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  tscEvaluation.registerPreTickEvaluationHooks(MinTicksPerTickSequenceHook(BUFFER_SIZE))
  tscEvaluation.registerMetricProviders(
      InvalidTSCInstancesPerTSCMetric(),
      StartingValidTSCInstancesPerTSCMetric(
          evaluationRunEntryId = java.util.UUID.fromString(runId)),
      TickCountMetric(),
      FirstTSCInstanceChangeMetric(
          evaluationRunEntryId = java.util.UUID.fromString(runId), tscEntryId = tscEntryId))

  // libsumo collector is per-process; never shared across JVMs
  val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

  // Main queue loop: claim work until empty
  while (true) {
    // Claim one chunk job (recommended) or one scenario job (if you keep 95M jobs).
    // val job = JobQueueRepository.claimNextChunk(runId, workerId) ?: break
    // job contains scenario IDs/range + mutantId

    val job =
        ChunkJobsRepository.claimNextChunkJob(runId = UUID.fromString(runId), workerId = workerId)
            ?: break

    try {
      for (sequenceNumber in job.seqFrom..job.seqTo) {
        val scenario = ScenarioStartingConfigurationRepository.getBySequenceNumber(sequenceNumber)
        checkNotNull(scenario) { "Scenario not found in database" }
        val runResult = libsumoDynamicDataCollector.runGeneratedScenario(scenario)
        tscEvaluation.runEvaluation(sequenceOf(runResult.asTickSequence()))
      }
      //       Mark job done
      ChunkJobsRepository.markDone(job.jobId)
    } catch (t: Throwable) {
      // JobQueueRepository.markFailed(job.id, t.stackTraceToString(), retry = job.attempts < 3)
      System.err.println("[$workerId] job failed: ${t.message}")
    }
  }

  println("[$workerId] finished")
}

private fun startWorkerProcess(workerId: String, evaluationRunId: String): Process {
  val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
  val classpath = System.getProperty("java.class.path")
  val mainClass = "tools.aqua.stars.coverage.significance.MainKt"

  val sumoHome = System.getenv("SUMO_HOME") ?: ""
  val javaLibraryPathArg = if (sumoHome.isNotBlank()) "-Djava.library.path=$sumoHome/bin" else null

  val cmd = buildList {
    add(javaBin)
    if (javaLibraryPathArg != null) add(javaLibraryPathArg)

    // optional: per-worker heap tuning
    add("-Xmx6g")

    add("-cp")
    add(classpath)
    add(mainClass)
    add("worker")
    add("--workerId=$workerId")
    add("--runId=$evaluationRunId")
  }

  println("Starting $workerId: ${cmd.joinToString(" ")}")
  return ProcessBuilder(cmd).inheritIO().start()
}

fun oldMain() {
  DbBootstrap.connectAndCreateSchema()
  val evaluationRunEntryId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())

  val scenarios =
      getGridTrafficScenarios(n = NUMBER_OF_SCENARIOS, seed = SEED, insertIntoDatabase = false)
  check(scenarios.size == NUMBER_OF_SCENARIOS) {
    "Expected $NUMBER_OF_SCENARIOS scenarios. Got ${scenarios.size}."
  }

  val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

  val staticTsc = staticTsc()
  val tscEntryId = TSCsRepository.upsertAndGetId(entry = staticTsc.toTSCEntry())

  val bucketCount = minOf(parallelism, scenarios.size.coerceAtLeast(1))
  val buckets = scenarios.buckets(bucketCount)

  println(
      "Split ${scenarios.size} scenarios into ${buckets.size} buckets (parallelism=$parallelism)")

  val pool = Executors.newFixedThreadPool(minOf(parallelism, buckets.size))

  try {
    val futures =
        buckets.mapIndexed { _, bucketScenarioFiles ->
          pool.submit(
              Callable {
                val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

                val scenarioRunResult =
                    libsumoDynamicDataCollector.runGeneratedScenario(bucketScenarioFiles.first())

                // Important: each bucket gets its own evaluator + metric instances
                val tscEvaluation =
                    TSCEvaluation(
                        staticTsc,
                        writePlots = false,
                        writePlotDataCSV = false,
                        writeSerializedResults = false,
                        compareToPreviousRun = false)

                tscEvaluation.registerPreTickEvaluationHooks(
                    MinTicksPerTickSequenceHook(BUFFER_SIZE))
                tscEvaluation.registerMetricProviders(
                    InvalidTSCInstancesPerTSCMetric(),
                    StartingValidTSCInstancesPerTSCMetric(
                        evaluationRunEntryId = evaluationRunEntryId),
                    TickCountMetric(),
                    FirstTSCInstanceChangeMetric(
                        evaluationRunEntryId = evaluationRunEntryId, tscEntryId = tscEntryId))

                tscEvaluation.runEvaluation(
                    listOf(listOf<TimeStep>().asTickSequence()).asSequence())
              })
        }

    // Propagate failures
    futures.forEach { it.get() }
  } finally {
    pool.shutdown()
  }
}
