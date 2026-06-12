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
import org.jetbrains.exposed.exceptions.ExposedSQLException
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficAnalysisJobsRepository
import tools.aqua.stars.coverage.significance.highayTrafficAnalysis.LibsumoDynamicDataCollectorForHighwayTrafficAnalysis
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.tsc.tsc
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Entry point for the highway traffic analysis worker.
 *
 * @param args Command-line arguments: --workerId=<workerId> --runId=<runId>
 *   --tscEntryId=<tscEntryId> --seedFromInclusive=<seedFromInclusive>
 *   --seedToExclusive=<seedToExclusive> --crowdiness=<crowdiness>
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)

  val workerId = CliArgs.requireString(args, "workerId")
  val runId = CliArgs.requireInt(args, "runId")
  val tscEntryId = CliArgs.requireInt(args, "tscEntryId")

  DbBootstrap.connect()

  val tsc = tsc()

  while (true) {
    val job = HighwayTrafficAnalysisJobsRepository.claimNextJob(runId, workerId) ?: break

    checkNotNull(job.jobId) { "No job found for runId=$runId and workerId=$workerId" }

    try {
      val collector =
          LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
              tsc = tsc,
              tscId = tscEntryId,
          )

      for (seed in job.seedFromInclusive until job.seedToExclusive) {
        collector.runHighwayTraffic(
            seed = seed,
            crowdiness = job.crowdiness,
        )
      }

      HighwayTrafficAnalysisJobsRepository.markDone(job.jobId)
    } catch (e: ExposedSQLException) {
      HighwayTrafficAnalysisJobsRepository.markFailedOrRequeue(
          job.jobId, e.stackTraceToString(), maxAttempts = 3)
      System.err.println("[$workerId] job failed: ${e.message}")
    } catch (e: SQLException) {
      HighwayTrafficAnalysisJobsRepository.markFailedOrRequeue(
          job.jobId, e.stackTraceToString(), maxAttempts = 3)
      System.err.println("[$workerId] job failed: ${e.message}")
    }
  }
}

/**
 * Starts a highway traffic analysis worker process.
 *
 * @param workerId The ID of the worker.
 * @param evaluationRunId The ID of the evaluation run.
 * @param tscEntryId The ID of the TSC entry.
 * @return The started process.
 */
fun startHighwayTrafficAnalysisWorkerProcess(
    workerId: String,
    evaluationRunId: Int,
    tscEntryId: Int
): Process =
    startJavaProcess(
        mainClass = "tools.aqua.stars.coverage.significance.workers.HighwayTrafficAnalysisKt",
        args =
            listOf(
                "--workerId=$workerId",
                "--runId=$evaluationRunId",
                "--tscEntryId=$tscEntryId",
                "--parentPid=${ProcessHandle.current().pid()}",
            ))
