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
import kotlin.math.roundToInt
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.seed.ChunkJobSeeder
import tools.aqua.stars.coverage.significance.db.seed.MutantGenerator
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.seedGridTrafficScenarios
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.coverage.significance.workers.startEvaluationWorkerProcess
import tools.aqua.stars.coverage.significance.workers.startStartingValidTSCInstancesWorkerProcess

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

/** List of processes started during the experiment. */
val workProcesses = mutableListOf<NamedProcess>()

/** Main entry point for the experiment. */
fun main(args: Array<String>) {
  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            // Kill all workers on JVM shutdown
            // Iterate copy to avoid concurrent modification
            workProcesses.toList().forEach { p -> p.killProcessTree() }
          })
  val debugSingleWorker = CliArgs.optionalBoolean(args, "debugSingleWorker", default = true)
  DbBootstrap.connectAndCreateSchema()

  val evaluationRunId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created evaluation run: $evaluationRunId")

  // Add TSC to db
  val tscEntryId = TSCsRepository.upsertAndGetId(entry = staticTsc().toTSCEntry())

  // Seed scenarios
  seedGridTrafficScenarios(seed = SEED, insertIntoDatabase = true)

  //  calculateMetric()
  // Precompute scenario-only metric once
  runStartingValidTSCInstancesEvaluation(parallelism = parallelism)

  //  // Run evaluation
  //  runEvaluation(
  //      evaluationRunId = evaluationRunId,
  //      debugSingleWorker = debugSingleWorker,
  //      tscEntryId = tscEntryId)
}

/**
 * Runs the main evaluation process.
 *
 * @param evaluationRunId ID of the evaluation run.
 * @param tscEntryId ID of the TSC to evaluate.
 * @param debugSingleWorker If true, runs only a single worker for debugging purposes.
 */
private fun runEvaluation(evaluationRunId: UUID, tscEntryId: UUID, debugSingleWorker: Boolean) {
  // Seed mutants + chunk jobs
  val mutantIds = MutantGenerator.seedIfEmpty()
  ChunkJobSeeder.seedChunks(runId = evaluationRunId, mutantIds = mutantIds, chunkSize = 1000L)

  val processes: List<NamedProcess> =
      if (debugSingleWorker) {
        listOf(
            NamedProcess(
                name = "worker-debug",
                process =
                    startEvaluationWorkerProcess(
                        workerId = "worker-debug",
                        evaluationRunId = evaluationRunId,
                        tscEntryId = tscEntryId)))
      } else {
        (0 until parallelism).map { idx ->
          NamedProcess(
              name = "worker-$idx",
              process =
                  startEvaluationWorkerProcess(
                      workerId = "worker-$idx",
                      evaluationRunId = evaluationRunId,
                      tscEntryId = tscEntryId))
        }
      }
  workProcesses += processes

  try {
    ProcessGroupRunner.awaitAll(groupLabel = "evaluation worker", processes = processes)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    processes.toList().forEach { it.killProcessTree() }
    throw e
  }
}

/**
 * Runs the evaluation of starting valid TSC instances in parallel.
 *
 * @param parallelism Number of parallel workers to use.
 */
private fun runStartingValidTSCInstancesEvaluation(parallelism: Int) {
  val maxSeq = ScenarioStartingConfigurationRepository.getMaxSequenceNumber()
  if (maxSeq <= 0L) {
    println("No scenarios found; skipping premetric phase.")
    return
  }

  val existingStartingValidTSCInstances: Long = MetricStartingValidTSCInstancesRepository.count()

  if (existingStartingValidTSCInstances != maxSeq) {
    MetricStartingValidTSCInstancesRepository.clearTable()
  }

  val workerCount = minOf(parallelism.coerceAtLeast(1), maxSeq.toInt().coerceAtLeast(1))
  val chunkSize = ((maxSeq + workerCount - 1) / workerCount).coerceAtLeast(1L)

  val processes: List<NamedProcess> =
      (0 until workerCount).map { i ->
        val from = i * chunkSize + 1
        val to = minOf((i + 1) * chunkSize, maxSeq)

        val name = "ValidStartingTSCInstancesWorker-$i"
        NamedProcess(
            name = name,
            process =
                startStartingValidTSCInstancesWorkerProcess(
                    workerId = name, seqFrom = from, seqTo = to))
      }
  workProcesses += processes
  try {
    ProcessGroupRunner.awaitAll(
        groupLabel = "ValidStartingTSCInstancesWorker", processes = processes)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    processes.toList().forEach { it.killProcessTree() }
    throw e
  }
}

/**
 * Starts a progress monitor thread that periodically fetches and displays the progress of chunk
 * jobs.
 *
 * @param runId The ID of the mutant scenario run.
 * @return The started [Thread] monitoring the progress.
 */
fun startProgressMonitor(runId: UUID): Thread {
  val t = Thread {
    var last = ""
    while (!Thread.currentThread().isInterrupted) {
      val p = transaction { MutantScenarioChunkJobsRepository.getProgress(runId) }
      val completed = p.done + p.failed
      val pct = if (p.total == 0L) 1.0 else completed.toDouble() / p.total.toDouble()

      val barWidth = 40
      val filled = (pct * barWidth).roundToInt().coerceIn(0, barWidth)
      val bar = "[" + "#".repeat(filled) + "-".repeat(barWidth - filled) + "]"

      val line =
          "\r$bar ${(pct * 100).toInt()}%  " +
              "done=${p.done} failed=${p.failed} running=${p.running} pending=${p.pending} total=${p.total}"

      if (line != last) {
        print(line)
        last = line
      }

      Thread.sleep(1000)
    }
  }
  t.name = "progress-monitor"
  t.isDaemon = true
  t.start()
  return t
}
