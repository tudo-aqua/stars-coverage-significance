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

import kotlin.collections.map
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.hooks.MaxTicksPerTickSequenceHook
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.installParentDeathWatcher
import tools.aqua.stars.coverage.significance.process.ProcessHelpers.startJavaProcess
import tools.aqua.stars.coverage.significance.staticTsc
import tools.aqua.stars.coverage.significance.utils.CliArgs
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * Worker for calculating the number of starting valid TSC instances per TSC metric.
 *
 * @param args Command line arguments.
 */
fun main(args: Array<String>) {
  installParentDeathWatcher(args)
  val workerId = CliArgs.requireString(args, "workerId")
  val seqFrom = CliArgs.requireLong(args, "seqFrom")
  val seqTo = CliArgs.requireLong(args, "seqTo")

  DbBootstrap.connectAndCreateSchema()
  println("[$workerId] startingValidTSCInstancesWorker started (seq=$seqFrom..$seqTo)")

  val staticTsc = staticTsc()

  val eval =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  eval.registerPreTickEvaluationHooks(
      MinTicksPerTickSequenceHook(1), MaxTicksPerTickSequenceHook(1))
  eval.registerMetricProviders(StartingValidTSCInstancesPerTSCMetric())

  val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

  val tickSequences = mutableListOf<TickSequence<TimeStep>>()
  val scenarios = db {
    (seqFrom..seqTo).map { ScenarioStartingConfigurationRepository.getBySequenceNumber(it) }
  }
  val consoleProgress = ConsoleProgress(scenarios.size, label = "Starting Valid TSC Instances")
  scenarios.forEachIndexed { index, scenario ->
    if (scenario == null) {
      System.err.println("scenario missing for index=$index")
      return@forEachIndexed
    }
    if (workerId == "ValidStartingTSCInstancesWorker-0") {
      consoleProgress.step()
    }
    val runResult = libsumoDynamicDataCollector.runGeneratedScenario(scenario, onlyFirstTick = true)
    tickSequences.add(runResult.asTickSequence())
  }

  eval.runEvaluation(tickSequences.asSequence())
}

/**
 * Starts a new process for the Starting Valid TSC Instances worker.
 *
 * @param workerId ID of the worker.
 * @param seqFrom Starting sequence number (inclusive).
 * @param seqTo Ending sequence number (inclusive).
 * @return The started [Process].
 */
fun startStartingValidTSCInstancesWorkerProcess(
    workerId: String,
    seqFrom: Long,
    seqTo: Long
): Process =
    startJavaProcess(
        mainClass =
            "tools.aqua.stars.coverage.significance.workers.StartingValidTSCInstancesWorkerKt",
        args =
            listOf(
                "--workerId=$workerId",
                "--seqFrom=$seqFrom",
                "--seqTo=$seqTo",
                "--parentPid=${ProcessHandle.current().pid()}",
            ))
