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
import tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.workers.startG0MutantCoverageReplayWorkerProcess

/**
 * Coordinator for the G0 mutant coverage replay analysis: spawns one worker process per available
 * core (each worker runs its own libsumo simulation — see the "Parallelism" section on
 * [G0MutantCoverageReplayAnalysis]), awaits them, then aggregates their streamed detail files into
 * one summary JSON.
 *
 * @param args Supports `--runId=<id>` (restrict to one evaluation run; omit for every run),
 *   `--bufferProcessors=<number>` (cores to reserve for buffering, default 0 — same convention as
 *   `RunEvaluation.kt`), `--aggregateOnly=true` to skip replaying entirely and just re-run
 *   aggregation against whatever detail files already exist under `details/` — e.g. to regenerate a
 *   summary that failed to copy/parse correctly, or to pick up a change to the aggregation logic
 *   itself, without repeating the (potentially multi-hour) replay — and `--leadTimeSeconds=<comma
 *   separated values>` (e.g. `0.2,0.5,0.7,1.0`) to additionally run one full replay+aggregate pass
 *   per lead time, each writing to its own `g0_mutant_coverage_replay_leadtime_<value>s/` folder,
 *   leaving the original (omit this flag) single-step `g0_mutant_coverage_replay/` output
 *   untouched. Passes run sequentially, each using the full available parallelism.
 */
fun main(args: Array<String>) {
  val runId = CliArgs.optionalInt(args, "runId")
  val aggregateOnly = CliArgs.optionalBoolean(args, "aggregateOnly", false)
  val bufferProcessors = (CliArgs.optionalInt(args, "bufferProcessors") ?: 0).coerceAtLeast(0)
  val leadTimes = CliArgs.optionalDoubleList(args, "leadTimeSeconds")

  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))

  // null (the original single-step behaviour, untouched) plus one pass per requested lead time.
  val passes: List<Double?> = leadTimes ?: listOf(null)
  passes.forEachIndexed { index, leadTimeSeconds ->
    println(
        "=== Pass ${index + 1}/${passes.size}" +
            (leadTimeSeconds?.let { " (leadTimeSeconds=$it)" } ?: " (original, no lead time)") +
            " ===")
    runOnePass(runId, aggregateOnly, bufferProcessors, leadTimeSeconds)
  }
}

/**
 * Runs one full replay (unless [aggregateOnly]) + aggregate pass for a single [leadTimeSeconds].
 */
private fun runOnePass(
    runId: Int?,
    aggregateOnly: Boolean,
    bufferProcessors: Int,
    leadTimeSeconds: Double?
) {
  if (aggregateOnly) {
    println(
        "--aggregateOnly: skipping replay, re-aggregating existing detail files for runId=${runId ?: "all"}.")
  } else {
    val parallelism =
        (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
    println(
        "Starting G0 mutant coverage replay with parallelism=$parallelism " +
            "(bufferProcessors=$bufferProcessors, runId=${runId ?: "all"}).")

    val processes: List<NamedProcess> =
        (0 until parallelism).map { workerId ->
          NamedProcess(
              name = "g0-replay-worker-$workerId",
              process =
                  startG0MutantCoverageReplayWorkerProcess(
                      workerId = workerId,
                      numWorkers = parallelism,
                      runId = runId,
                      leadTimeSeconds = leadTimeSeconds))
        }
    try {
      ProcessGroupRunner.awaitAll(
          groupLabel = "G0 mutant coverage replay worker", processes = processes)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      processes.forEach { it.killProcessTree() }
      throw e
    }
  }

  val summary = G0MutantCoverageReplayAnalysis.aggregate(runId, leadTimeSeconds)
  println(
      "Finished! ${summary.totalTicksAnalyzed} ticks analyzed, " +
          "${summary.originalMutantReproducedCount} original-mutant kills reproduced, " +
          "${summary.unavoidableTickCount} unavoidable ticks, " +
          "${summary.mutantsWithNewKillsCount}/${summary.totalMutants} mutants gained new kills.")
}
