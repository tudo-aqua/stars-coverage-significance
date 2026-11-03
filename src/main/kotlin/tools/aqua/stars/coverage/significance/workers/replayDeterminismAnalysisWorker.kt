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

import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.postEvaluation.ReplayDeterminismAnalysis
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Entry point for one replay-determinism-analysis worker process.
 *
 * One libsumo simulation exists per process (see the "Parallelism" section on
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis`), so —
 * exactly like `g0FullLeadTimeSweepAnalysisWorker.kt` — each worker runs in its own JVM process
 * rather than as a thread, deterministically claiming every Nth entry of the sample.
 *
 * @param args Command-line arguments: `--workerId=<id>` `--numWorkers=<n>` `--ticksPerMutant=<n>`
 *   `--maxLeadTimeSteps=<n>`, and optionally `--runId=<id>` `--maxMutants=<n>`
 *   `--maxNonMonotonicSample=<n>`.
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)
  DbBootstrap.connect()

  val workerId = CliArgs.requireInt(args, "workerId")
  val numWorkers = CliArgs.requireInt(args, "numWorkers")
  val runId = CliArgs.optionalInt(args, "runId")
  val ticksPerMutant = CliArgs.requireInt(args, "ticksPerMutant")
  val maxMutants = CliArgs.optionalInt(args, "maxMutants")
  val maxNonMonotonicSample = CliArgs.optionalInt(args, "maxNonMonotonicSample") ?: 100
  val maxLeadTimeSteps = CliArgs.requireInt(args, "maxLeadTimeSteps")

  println(
      "Starting replay determinism analysis worker: workerId=$workerId, numWorkers=$numWorkers, " +
          "runId=$runId, ticksPerMutant=$ticksPerMutant, maxMutants=$maxMutants, " +
          "maxNonMonotonicSample=$maxNonMonotonicSample, maxLeadTimeSteps=$maxLeadTimeSteps")

  ReplayDeterminismAnalysis.runWorkerSlice(
      runId,
      workerId,
      numWorkers,
      ticksPerMutant,
      maxMutants,
      maxNonMonotonicSample,
      maxLeadTimeSteps)
}

/**
 * Starts a replay-determinism-analysis worker process.
 *
 * @param workerId The index of this worker, in `0 until numWorkers`.
 * @param numWorkers Total number of workers splitting the sample.
 * @param runId Evaluation run id to restrict to, or `null` to include every run.
 * @param ticksPerMutant Random-sample depth per mutant.
 * @param maxMutants Cap on distinct mutants sampled, or `null` for every mutant with a flagged
 *   tick.
 * @param maxNonMonotonicSample Cap on the targeted sample drawn from the existing
 *   `nonMonotonicTickIds`.
 * @param maxLeadTimeSteps Cap on lead-time depth (in 0.1s steps) per sampled tick.
 * @return The started process.
 */
fun startReplayDeterminismAnalysisWorkerProcess(
    workerId: Int,
    numWorkers: Int,
    runId: Int?,
    ticksPerMutant: Int,
    maxMutants: Int?,
    maxNonMonotonicSample: Int,
    maxLeadTimeSteps: Int,
): Process =
    startJavaProcess(
        mainClass =
            "tools.aqua.stars.coverage.significance.workers.ReplayDeterminismAnalysisWorkerKt",
        args =
            buildList {
              add("--workerId=$workerId")
              add("--numWorkers=$numWorkers")
              if (runId != null) add("--runId=$runId")
              add("--ticksPerMutant=$ticksPerMutant")
              if (maxMutants != null) add("--maxMutants=$maxMutants")
              add("--maxNonMonotonicSample=$maxNonMonotonicSample")
              add("--maxLeadTimeSteps=$maxLeadTimeSteps")
              add("--parentPid=${ProcessHandle.current().pid()}")
            })
