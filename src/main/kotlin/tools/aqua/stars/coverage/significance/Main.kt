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
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.seed.ChunkJobSeeder
import tools.aqua.stars.coverage.significance.db.seed.MutantGenerator
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.seedGridTrafficScenarios
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.coverage.significance.workers.startEvaluationWorkerProcess
import tools.aqua.stars.coverage.significance.workers.startStartingValidTSCInstancesWorkerProcess
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

/** List of processes started during the experiment. */
val workProcesses = mutableListOf<NamedProcess>()

/** Main entry point for the experiment. */
fun main() {
  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            // Kill all workers on JVM shutdown
            // Iterate copy to avoid concurrent modification
            workProcesses.toList().forEach { p -> p.killProcessTree() }
          })
  DbBootstrap.connectAndCreateSchema()

  // Add TSC to db
  val tscEntryId = TSCsRepository.upsertAndGetId(entry = staticTsc().toTSCEntry())

  // Seed scenarios
  seedGridTrafficScenarios(seed = SEED, insertIntoDatabase = true)

  // Precompute scenario-only metric once
  runStartingValidTSCInstancesEvaluation(parallelism = parallelism)

  // Seed mutants
  val mutantIds = MutantGenerator.seed()

  // Run evaluation
  //  runEvaluation(tscEntryId = tscEntryId, mutantIds = mutantIds)
  val evaluationRunId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created evaluation run: $evaluationRunId")

  // Clear Table for convenience
  MutantScenarioChunkJobsRepository.clearTable()

  println("Seeding chunk jobs...")
  ChunkJobSeeder.seedChunks(
      runId = evaluationRunId, mutantIds = mutantIds.take(1), chunkSize = 1000L, scenarioCount = 10)

  DbBootstrap.connectAndCreateSchema()

  val staticTsc = staticTsc()

  val eval =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  //  eval.registerPreTickEvaluationHooks(MinTicksPerTickSequenceHook(BUFFER_SIZE))
  eval.registerMetricProviders(
      FirstTSCInstanceChangeMetric(evaluationRunEntryId = evaluationRunId, tscEntryId = tscEntryId),
  )

  val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

  while (true) {
    val job =
        MutantScenarioChunkJobsRepository.claimNextChunkJob(
            runId = evaluationRunId, workerId = "workerId") ?: break

    checkNotNull(job.jobId) {
      "No chunk job found for runId=$evaluationRunId and workerId=workerId"
    }

    val tickSequences = mutableListOf<TickSequence<TimeStep>>()
    val scenarios = db {
      (job.seqFrom..job.seqTo).map {
        ScenarioStartingConfigurationRepository.getBySequenceNumber(it)
      }
    }
    scenarios.forEachIndexed { index, scenario ->
      if (scenario == null) {
        System.err.println("scenario missing for index=$index")
        return@forEachIndexed
      }
      val runResult =
          libsumoDynamicDataCollector.runGeneratedScenario(
              evaluationRunId, scenario, job.mutantId, onlyFirstTick = false)
      tickSequences.add(runResult.asTickSequence(bufferSize = BUFFER_SIZE))
    }
    eval.runEvaluation(tickSequences.asSequence())
    MutantScenarioChunkJobsRepository.markDone(job.jobId)
  }
}

/**
 * Runs the main evaluation process.
 *
 * @param tscEntryId ID of the TSC to evaluate.
 * @param mutantIds IDs of the mutants to evaluate.
 */
private fun runEvaluation(tscEntryId: UUID, mutantIds: List<UUID>) {
  val evaluationRunId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created evaluation run: $evaluationRunId")

  // Clear Table for convenience
  MutantScenarioChunkJobsRepository.clearTable()

  println("Seeding chunk jobs...")
  ChunkJobSeeder.seedChunks(
      runId = evaluationRunId, mutantIds = mutantIds, chunkSize = 1_000L, 1_000_000)

  startProgressMonitor(evaluationRunId)

  val processes: List<NamedProcess> =
      (0 until parallelism).map { idx ->
        NamedProcess(
            name = "worker-$idx",
            process =
                startEvaluationWorkerProcess(
                    workerId = "worker-$idx",
                    evaluationRunId = evaluationRunId,
                    tscEntryId = tscEntryId))
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

  if (existingStartingValidTSCInstances == maxSeq) {
    println("All starting valid TSC instances already exist; skipping calculation.")
    return
  }

  MetricStartingValidTSCInstancesRepository.clearTable()

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
    val startedAtMs = System.currentTimeMillis()

    // We start estimating only once we observe the first completion (done+failed > 0).
    var firstCompletionAtMs: Long? = null
    var completedAtFirstCompletion: Long = 0L

    fun formatDuration(secondsTotal: Long): String {
      val s = secondsTotal.coerceAtLeast(0)
      val h = s / 3600
      val m = (s % 3600) / 60
      val sec = s % 60
      return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, sec)
        m > 0 -> "%dm %02ds".format(m, sec)
        else -> "%ds".format(sec)
      }
    }

    while (!Thread.currentThread().isInterrupted) {
      val p = transaction { MutantScenarioChunkJobsRepository.getProgress(runId) }

      val completed = p.done + p.failed
      val total = p.total
      val nowMs = System.currentTimeMillis()

      if (firstCompletionAtMs == null && completed > 0L) {
        firstCompletionAtMs = nowMs
        completedAtFirstCompletion = completed
      }

      val pct =
          if (total == 0L) 1.0 else (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)

      val barWidth = 40
      val filled = (pct * barWidth).roundToInt().coerceIn(0, barWidth)
      val bar = "[" + "#".repeat(filled) + "-".repeat(barWidth - filled) + "]"

      val base =
          "\r$bar ${(pct * 100).toInt()}%  " +
              "done=${p.done} failed=${p.failed} running=${p.running} pending=${p.pending} total=$total"

      val line = run {
        val fcMs = firstCompletionAtMs
        if (fcMs == null || total <= 0L) {
          // No estimates until at least one job has completed.
          base
        } else {
          val elapsedSec = ((nowMs - startedAtMs) / 1000.0).roundToInt().toLong()

          // Throughput since first completion, using completions gained since that moment.
          val dtSec = ((nowMs - fcMs) / 1000.0).coerceAtLeast(1.0)
          val dCompleted = (completed - completedAtFirstCompletion).coerceAtLeast(0L)

          // If dCompleted is still 0 (e.g., exactly one job finished and nothing else yet),
          // keep waiting rather than printing unstable ETAs.
          if (dCompleted == 0L) {
            base + "  elapsed=${formatDuration(elapsedSec)}  eta=estimating…"
          } else {
            val rate = dCompleted.toDouble() / dtSec // jobs per second
            val remaining = (total - completed).coerceAtLeast(0L)
            val remainingSec =
                if (rate > 0.0) (remaining / rate).roundToInt().toLong() else Long.MAX_VALUE
            val totalSec = elapsedSec + remainingSec

            base +
                "  elapsed=${formatDuration(elapsedSec)}" +
                "  remaining=${formatDuration(remainingSec)}" +
                "  total≈${formatDuration(totalSec)}"
          }
        }
      }

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
