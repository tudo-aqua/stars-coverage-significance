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

import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.workers.startG0DivergenceLeadTimeAnalysisWorkerProcess

/**
 * Coordinator for the G0 lead-time divergence analysis: spawns one worker process per available
 * core (each worker runs its own libsumo simulation — see the "Parallelism" section on
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis]), awaits
 * them, then aggregates their streamed detail files into one summary JSON.
 *
 * Unlike `RunG0MutantCoverageReplay.kt`, there is only ever one pass: each flagged tick's own
 * backward lead-time sweep (0.0, 0.1, 0.2, ... seconds) happens internally per tick — see
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis].
 *
 * @param args Supports `--runId=<id>` (restrict to one evaluation run; omit for every run),
 *   `--bufferProcessors=<number>` (cores to reserve for buffering, default 0 — same convention as
 *   `RunEvaluation.kt`), and `--aggregateOnly=true` to skip the sweep entirely and just re-run
 *   aggregation against whatever detail files already exist under `details/`.
 */
fun main(args: Array<String>) {
  val runId = CliArgs.optionalInt(args, "runId")
  val aggregateOnly = CliArgs.optionalBoolean(args, "aggregateOnly", false)
  val bufferProcessors = (CliArgs.optionalInt(args, "bufferProcessors") ?: 0).coerceAtLeast(0)

  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))

  println(
      "Got the following arguments: runId = $runId, aggregateOnly = $aggregateOnly, bufferProcessors = $bufferProcessors")

  if (aggregateOnly) {
    println(
        "--aggregateOnly: skipping sweep, re-aggregating existing detail files for runId=${runId ?: "all"}.")
  } else {
    val parallelism =
        (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
    println(
        "Starting G0 lead-time divergence analysis with parallelism=$parallelism " +
            "(bufferProcessors=$bufferProcessors, runId=${runId ?: "all"}).")

    val processes: List<NamedProcess> =
        (0 until parallelism).map { workerId ->
          NamedProcess(
              name = "g0-divergence-worker-$workerId",
              process =
                  startG0DivergenceLeadTimeAnalysisWorkerProcess(
                      workerId = workerId, numWorkers = parallelism, runId = runId))
        }
    try {
      ProcessGroupRunner.awaitAll(
          groupLabel = "G0 lead-time divergence analysis worker", processes = processes)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      processes.forEach { it.killProcessTree() }
      throw e
    }
  }

  val summary = G0DivergenceLeadTimeAnalysis.aggregate(runId)
  println(
      "Finished! ${summary.totalTicksAnalyzed} ticks analyzed, " +
          "${summary.divergedCount} diverged (${summary.immediateDivergenceCount} immediately), " +
          "${summary.egoLeftSimulationCount} inconclusive (ego left simulation), " +
          "${summary.reachedScenarioStartCount} reproduced through to the scenario start. " +
          "Percentiles: ${summary.percentiles}.")
}
