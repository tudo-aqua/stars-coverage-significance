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
import tools.aqua.stars.coverage.significance.postEvaluation.ReplayDeterminismAnalysis
import tools.aqua.stars.coverage.significance.postEvaluation.ReplayDiagnosticsReport
import tools.aqua.stars.coverage.significance.postEvaluation.ResumeFailureAggregation
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.workers.startReplayDeterminismAnalysisWorkerProcess

/**
 * Coordinator for the replay determinism & resume-failure diagnostics: answers two questions a
 * reviewer of the G0 full lead-time sweep results raised — (1) is replaying/reconstructing a
 * simulation from a recorded tick actually deterministic, tested with a small, broad sample of
 * ticks rather than the full multi-hour sweep, and (2) why is it often not possible to resume from
 * a later tick, re-using the existing sweep's already-collected data for free.
 *
 * Spawns one worker process per available core (each worker runs its own libsumo simulation — see
 * the "Parallelism" section on
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis`), awaits
 * them, re-aggregates the EXISTING `g0_full_lead_time_sweep` detail files (read-only), then writes
 * one Markdown report tying both findings together.
 *
 * @param args Supports `--runId=<id>` (restrict to one evaluation run; omit for every run),
 *   `--bufferProcessors=<number>` (cores to reserve for buffering, default 0),
 *   `--aggregateOnly=true` to skip the live double-replay sweep and just re-read whatever detail
 *   files already exist under `replay_diagnostics/details/`, `--ticksPerMutant=<n>` (random-sample
 *   depth per mutant, default 3), `--maxMutants=<n>` (cap on distinct mutants sampled, default
 *   unset = every mutant with a flagged tick), `--maxNonMonotonicSample=<n>` (cap on the targeted
 *   sample drawn from the existing sweep's `nonMonotonicTickIds`, default 100),
 *   `--maxLeadTimeSteps=<n>` (cap on lead-time depth per sampled tick, default 10 — the "reduced
 *   tick count" knob), `--checkDbCounts=true` (opt-in DB-vs-detail-file sanity check for the
 *   resume-failure re-aggregation), and `--skipResumeFailureAggregation=true` (report Part A only).
 */
fun main(args: Array<String>) {
  val runId = CliArgs.optionalInt(args, "runId")
  val bufferProcessors = (CliArgs.optionalInt(args, "bufferProcessors") ?: 0).coerceAtLeast(0)
  val aggregateOnly = CliArgs.optionalBoolean(args, "aggregateOnly", false)
  val ticksPerMutant = (CliArgs.optionalInt(args, "ticksPerMutant") ?: 3).coerceAtLeast(1)
  val maxMutants = CliArgs.optionalInt(args, "maxMutants")
  val maxNonMonotonicSample =
      (CliArgs.optionalInt(args, "maxNonMonotonicSample") ?: 100).coerceAtLeast(0)
  val maxLeadTimeSteps = (CliArgs.optionalInt(args, "maxLeadTimeSteps") ?: 10).coerceAtLeast(1)
  val checkDbCounts = CliArgs.optionalBoolean(args, "checkDbCounts", false)
  val skipResumeFailureAggregation =
      CliArgs.optionalBoolean(args, "skipResumeFailureAggregation", false)

  println(
      "Got the following arguments: runId=$runId, aggregateOnly=$aggregateOnly, " +
          "bufferProcessors=$bufferProcessors, ticksPerMutant=$ticksPerMutant, maxMutants=$maxMutants, " +
          "maxNonMonotonicSample=$maxNonMonotonicSample, maxLeadTimeSteps=$maxLeadTimeSteps, " +
          "checkDbCounts=$checkDbCounts, skipResumeFailureAggregation=$skipResumeFailureAggregation")

  if (aggregateOnly) {
    println(
        "--aggregateOnly: skipping the live sweep, re-aggregating existing detail files for runId=${runId ?: "all"}.")
    if (checkDbCounts) DbBootstrap.connect()
  } else {
    DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))
    val parallelism =
        (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
    println(
        "Starting replay determinism analysis with parallelism=$parallelism " +
            "(bufferProcessors=$bufferProcessors, runId=${runId ?: "all"}).")

    val processes: List<NamedProcess> =
        (0 until parallelism).map { workerId ->
          NamedProcess(
              name = "replay-determinism-worker-$workerId",
              process =
                  startReplayDeterminismAnalysisWorkerProcess(
                      workerId = workerId,
                      numWorkers = parallelism,
                      runId = runId,
                      ticksPerMutant = ticksPerMutant,
                      maxMutants = maxMutants,
                      maxNonMonotonicSample = maxNonMonotonicSample,
                      maxLeadTimeSteps = maxLeadTimeSteps,
                  ))
        }
    try {
      ProcessGroupRunner.awaitAll(
          groupLabel = "Replay determinism analysis worker", processes = processes)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      processes.forEach { it.killProcessTree() }
      throw e
    }
  }

  val determinismSummary = ReplayDeterminismAnalysis.aggregate(runId)
  val resumeFailureSummary =
      if (skipResumeFailureAggregation) null
      else ResumeFailureAggregation.aggregate(runId, checkDbCounts)
  val reportPath =
      ReplayDiagnosticsReport.write(
          runId,
          ticksPerMutant,
          maxMutants,
          maxNonMonotonicSample,
          maxLeadTimeSteps,
          determinismSummary,
          resumeFailureSummary)

  println(
      "Finished! ${determinismSummary.totalAttempts} double-replay attempts across " +
          "${determinismSummary.totalTicksSampled} ticks / ${determinismSummary.mutantsSampled} mutants. " +
          "Outcome counts: ${determinismSummary.outcomeCounts}." +
          (if (resumeFailureSummary != null)
              " Resume-failure: ${resumeFailureSummary.overall.inconclusiveCount} inconclusive / " +
                  "${resumeFailureSummary.overall.totalSteps} total steps."
          else "") +
          " Report: $reportPath")
}
