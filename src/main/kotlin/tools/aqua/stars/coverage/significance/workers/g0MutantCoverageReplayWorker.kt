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
import tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Entry point for one G0 mutant coverage replay worker process.
 *
 * One libsumo simulation exists per process (see the "Parallelism" section on
 * [G0MutantCoverageReplayAnalysis]), so — exactly like `evaluationWorker.kt` — each worker runs in
 * its own JVM process rather than as a thread, deterministically claiming every Nth flagged tick.
 *
 * @param args Command-line arguments: `--workerId=<id>` `--numWorkers=<n>` and optionally
 *   `--runId=<id>`.
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)
  DbBootstrap.connect()

  val workerId = CliArgs.requireInt(args, "workerId")
  val numWorkers = CliArgs.requireInt(args, "numWorkers")
  val runId = CliArgs.optionalInt(args, "runId")

  G0MutantCoverageReplayAnalysis.runWorkerSlice(runId, workerId, numWorkers)
}

/**
 * Starts a G0 mutant coverage replay worker process.
 *
 * @param workerId The index of this worker, in `0 until numWorkers`.
 * @param numWorkers Total number of workers splitting the flagged-tick list.
 * @param runId Evaluation run id to restrict to, or `null` to include every run.
 * @return The started process.
 */
fun startG0MutantCoverageReplayWorkerProcess(workerId: Int, numWorkers: Int, runId: Int?): Process =
    startJavaProcess(
        mainClass = "tools.aqua.stars.coverage.significance.workers.G0MutantCoverageReplayWorkerKt",
        args =
            buildList {
              add("--workerId=$workerId")
              add("--numWorkers=$numWorkers")
              if (runId != null) add("--runId=$runId")
              add("--parentPid=${ProcessHandle.current().pid()}")
            })
