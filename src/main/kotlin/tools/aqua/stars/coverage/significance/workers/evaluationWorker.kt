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

package tools.aqua.stars.coverage.significance.workers

import java.sql.SQLException
import kotlin.collections.map
import kotlin.random.Random
import org.jetbrains.exposed.exceptions.ExposedSQLException
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.MAX_LENGTH_OF_SCENARIO_IN_SECONDS
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsMetric
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.tscListToUseInProject
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * Entry point for the evaluation worker.
 *
 * @param args Command-line arguments: --workerId=<workerId> --runId=<runId>
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)
  DbBootstrap.connect()

  val finalArgs =
      if (args.isEmpty() || args.all { it.isBlank() }) {
        arrayOf("--workerId=1", "--runId=${EvaluationRunsRepository.getLatest()?.id}")
      } else {
        args
      }

  val workerId = CliArgs.requireString(finalArgs, "workerId")
  val runId = CliArgs.requireInt(finalArgs, "runId")

  while (true) {
    val job =
        MutantScenarioChunkJobsRepository.claimNextChunkJob(runId = runId, workerId = workerId)
            ?: break

    checkNotNull(job.jobId) { "No chunk job found for runId=$runId and workerId=$workerId" }

    try {
      val eval =
          TSCEvaluation(
              tscListToUseInProject,
              writePlots = false,
              writePlotDataCSV = false,
              writeSerializedResults = false,
              compareToPreviousRun = false)

      eval.registerPreTickEvaluationHooks(
          MinTicksPerTickSequenceHook(1),
          MaxSecondsEvaluationHook(maxSeconds = MAX_LENGTH_OF_SCENARIO_IN_SECONDS.toInt()))

      eval.registerMetricProviders(FailedMonitorsMetric())

      val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()
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
                runId = runId,
                scenario,
                job.mutantId,
                maxLengthOfScenarioInSeconds = MAX_LENGTH_OF_SCENARIO_IN_SECONDS)
        tickSequences.add(
            runResult.asTickSequence(
                scenario.id.toString(),
                bufferSize = BUFFER_SIZE,
                iterationOrder = TickSequence.IterationOrder.BACKWARD,
                iterationMode = TickSequence.IterationMode.END_FILLED))
      }
      eval.runEvaluation(tickSequences.asSequence())

      MutantScenarioChunkJobsRepository.markDone(job.jobId)
    } catch (e: ExposedSQLException) {
      System.err.println("[$workerId] job failed: ${e.message}")
      requeueAfterFailure(job.jobId, e.stackTraceToString(), workerId)
    } catch (exception: SQLException) {
      System.err.println("[$workerId] job failed: ${exception.message}")
      requeueAfterFailure(job.jobId, exception.stackTraceToString(), workerId)
    }
  }
}

/**
 * Records the failed job's failure (by [jobId]) and requeues it (up to 3 attempts) for another
 * worker to pick up, then backs off with jitter before this worker's loop reclaims its next job.
 *
 * Both steps are defensive against the DB still being unavailable: if a transient connection issue
 * (e.g. a saturated PgBouncer under many parallel worker processes) caused the original failure,
 * [MutantScenarioChunkJobsRepository.markFailedOrRequeue] itself needs a DB connection and could
 * throw too — left uncaught, that would crash this whole worker process instead of letting it back
 * off and try again. The backoff (with random jitter, not a fixed delay) also matters on its own:
 * without it, a failed job goes straight back to `PENDING` with no delay, so the very next loop
 * iteration can reclaim and re-fail it immediately, turning a brief DB blip into a tight retry
 * storm that hammers the DB harder right when it's already struggling — jitter spreads many
 * workers' retries out instead of them all hammering PgBouncer in lockstep.
 *
 * @param jobId The failed job's id.
 * @param error Stack trace text to record against the job.
 * @param workerId This worker's id, for the log message if requeuing itself fails.
 */
private fun requeueAfterFailure(jobId: Long, error: String, workerId: String) {
  try {
    MutantScenarioChunkJobsRepository.markFailedOrRequeue(jobId, error, maxAttempts = 3)
  } catch (e: SQLException) {
    System.err.println(
        "[$workerId] failed to record job failure (DB still unavailable?): ${e.message}")
  }
  Thread.sleep(2_000L + Random.nextLong(3_000L))
}

/**
 * Starts an evaluation worker process.
 *
 * @param workerId The ID of the worker.
 * @param evaluationRunId The ID of the evaluation run.
 * @return The started process.
 */
fun startEvaluationWorkerProcess(workerId: String, evaluationRunId: Int): Process =
    startJavaProcess(
        mainClass = "tools.aqua.stars.coverage.significance.workers.EvaluationWorkerKt",
        args =
            listOf(
                "--workerId=$workerId",
                "--runId=$evaluationRunId",
                "--parentPid=${ProcessHandle.current().pid()}",
            ))
