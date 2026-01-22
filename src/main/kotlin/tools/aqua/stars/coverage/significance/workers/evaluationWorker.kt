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
import java.util.UUID
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.core.metrics.evaluation.TickCountMetric
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.staticTsc
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * Entry point for the evaluation worker.
 *
 * @param args Command-line arguments: --workerId=<workerId> --runId=<runId>
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)
  val workerId = CliArgs.requireString(args, "workerId")
  val runId = CliArgs.requireUuid(args, "runId")
  val tscEntryId = CliArgs.requireUuid(args, "tscEntryId")

  DbBootstrap.connectAndCreateSchema()
  println("[$workerId] worker started (runId=$runId)")

  val staticTsc = staticTsc()

  val eval =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  eval.registerPreTickEvaluationHooks(MinTicksPerTickSequenceHook(BUFFER_SIZE))
  eval.registerMetricProviders(
      InvalidTSCInstancesPerTSCMetric(),
      TickCountMetric(),
      FirstTSCInstanceChangeMetric(evaluationRunEntryId = runId, tscEntryId = tscEntryId),
  )

  val collector = LibsumoDynamicDataCollector()

  while (true) {
    val job =
        MutantScenarioChunkJobsRepository.claimNextChunkJob(runId = runId, workerId = workerId)
            ?: break

    try {
      for (sequenceNumber in job.seqFrom..job.seqTo) {
        val scenario = ScenarioStartingConfigurationRepository.getBySequenceNumber(sequenceNumber)
        checkNotNull(scenario) { "Scenario not found for sequenceNumber=$sequenceNumber" }

        val runResult = collector.runGeneratedScenario(scenario)
        eval.runEvaluation(sequenceOf(runResult.asTickSequence()))
      }

      MutantScenarioChunkJobsRepository.markDone(job.jobId)
    } catch (exception: SQLException) {
      MutantScenarioChunkJobsRepository.markFailedOrRequeue(
          job.jobId, exception.stackTraceToString(), maxAttempts = 3)
      System.err.println("[$workerId] job failed: ${exception.message}")
    }
  }

  println("[$workerId] worker finished")
}

/**
 * Starts an evaluation worker process.
 *
 * @param workerId The ID of the worker.
 * @param evaluationRunId The ID of the evaluation run.
 * @param tscEntryId The ID of the TSC entry.
 * @return The started process.
 */
fun startEvaluationWorkerProcess(
    workerId: String,
    evaluationRunId: UUID,
    tscEntryId: UUID
): Process =
    startJavaProcess(
        mainClass = "tools.aqua.stars.coverage.significance.workers.EvaluationWorkerKt",
        args =
            listOf(
                "--workerId=$workerId",
                "--runId=$evaluationRunId",
                "--tscEntryId=$tscEntryId",
                "--parentPid=${ProcessHandle.current().pid()}",
            ))
