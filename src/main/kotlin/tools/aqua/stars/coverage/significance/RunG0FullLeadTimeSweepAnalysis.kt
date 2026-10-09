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
import tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.workers.startG0FullLeadTimeSweepAnalysisWorkerProcess

/**
 * Coordinator for the G0 full lead-time sweep analysis: spawns one worker process per available
 * core (each worker runs its own libsumo simulation — see the "Parallelism" section on
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis]), awaits
 * them, then aggregates their streamed detail files into one summary JSON.
 *
 * Unlike [tools.aqua.stars.coverage.significance.RunG0DivergenceLeadTimeAnalysis]'s sweep, every
 * tick is swept all the way back to its scenario's start rather than stopping at the first lead
 * time that fails to reproduce the recorded failure — see
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis] for why, and
 * expect a correspondingly longer run time.
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

  println(
      "Got the following arguments: runId = $runId, aggregateOnly = $aggregateOnly, bufferProcessors = $bufferProcessors")

  if (aggregateOnly) {
    // Aggregation only reads back already-written detail files (see
    // G0FullLeadTimeSweepAnalysis.aggregate) - no DB access needed, so a live Postgres instance
    // isn't required just to regenerate a summary from existing data.
    println(
        "--aggregateOnly: skipping sweep, re-aggregating existing detail files for runId=${runId ?: "all"}.")
  } else {
    DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))
    val parallelism =
        (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
    println(
        "Starting G0 full lead-time sweep analysis with parallelism=$parallelism " +
            "(bufferProcessors=$bufferProcessors, runId=${runId ?: "all"}).")

    val processes: List<NamedProcess> =
        (0 until parallelism).map { workerId ->
          NamedProcess(
              name = "g0-full-sweep-worker-$workerId",
              process =
                  startG0FullLeadTimeSweepAnalysisWorkerProcess(
                      workerId = workerId, numWorkers = parallelism, runId = runId))
        }
    try {
      ProcessGroupRunner.awaitAll(
          groupLabel = "G0 full lead-time sweep analysis worker", processes = processes)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      processes.forEach { it.killProcessTree() }
      throw e
    }
  }

  val summary = G0FullLeadTimeSweepAnalysis.aggregate(runId)
  println(
      "Finished! ${summary.totalTicksAnalyzed} ticks analyzed, " +
          "${summary.neverReproducedCount} never reproduced, " +
          "${summary.nonMonotonicCount} non-monotonic (reproduction turned back on after " +
          "failing at a nearer lead time). Min-lead-time percentiles: " +
          "${summary.minLeadTimePercentiles}. Max-lead-time percentiles: " +
          "${summary.maxLeadTimePercentiles}.")
}
