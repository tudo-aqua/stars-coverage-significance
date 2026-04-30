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
import kotlin.collections.map
import org.jetbrains.exposed.exceptions.ExposedSQLException
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.TotalTickDifferenceMetric
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.MAX_LENGTH_OF_SCENARIO_IN_SECONDS
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricTotalTickDifferenceEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricTotalTickDifferenceRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsMetric
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.tscListToUseInProject
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
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

  DbBootstrap.connect()

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
          MinTicksPerTickSequenceHook(2),
          MaxSecondsEvaluationHook(maxSeconds = MAX_LENGTH_OF_SCENARIO_IN_SECONDS.toInt()))

      val totalTickDifferenceMetric =
          TotalTickDifferenceMetric<
              Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>()

      eval.registerMetricProviders(
          FirstTSCInstanceChangeMetric(evaluationRunEntryId = runId, tscEntryId = tscEntryId),
          FailedMonitorsMetric(tscId = tscEntryId),
          totalTickDifferenceMetric)

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

      val totalTickDifferences = totalTickDifferenceMetric.getState()
      db {
        totalTickDifferences.map { (identifier, tickDifference) ->
          val dbEntry =
              MetricTotalTickDifferenceEntry(
                  tscId = tscEntryId,
                  mutantId = job.mutantId,
                  runId = runId,
                  scenarioConfigId = UUID.fromString(identifier),
                  totalTickDifferenceMillis = tickDifference?.differenceMillis ?: 0L)
          MetricTotalTickDifferenceRepository.insertIfMissingAndReturnId(dbEntry)
        }
      }

      MutantScenarioChunkJobsRepository.markDone(job.jobId)
    } catch (e: ExposedSQLException) {
      MutantScenarioChunkJobsRepository.markFailedOrRequeue(
          job.jobId, e.stackTraceToString(), maxAttempts = 3)
      System.err.println("[$workerId] job failed: ${e.message}")
    } catch (exception: SQLException) {
      MutantScenarioChunkJobsRepository.markFailedOrRequeue(
          job.jobId, exception.stackTraceToString(), maxAttempts = 3)
      System.err.println("[$workerId] job failed: ${exception.message}")
    }
  }
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
