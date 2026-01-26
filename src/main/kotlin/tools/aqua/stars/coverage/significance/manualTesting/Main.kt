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

package tools.aqua.stars.coverage.significance.manualTesting

import java.util.*
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsMetric
import tools.aqua.stars.coverage.significance.staticTsc

fun main() {
  DbBootstrap.connectAndCreateSchema()
  val numberOfScenarios = 1
  val seed = 1
  val scenarios = generateGridTrafficScenariosTest(seed = seed)

  val libsumoDynamicDataCollector = LibsumoDynamicDataCollectorTest()

  val libSumoTicks =
      libsumoDynamicDataCollector.runGeneratedScenario(
          runId = UUID.randomUUID(),
          scenario = scenarios.first().toScenarioStartingConfigurationEntry(UUID.randomUUID()),
          mutant = Mutant())

  println(
      """
    python "C:\Program Files (x86)\Eclipse\Sumo\tools\fcdReplay.py" -k replay.sumocfg -f ${scenarios.first().id}.fcd.xml
  """
          .trimIndent())

  val ticks = listOf(libSumoTicks.asTickSequence(BUFFER_SIZE)).asSequence()
  val staticTsc = staticTsc()

  val eval =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  eval.registerPreTickEvaluationHooks(MinTicksPerTickSequenceHook(BUFFER_SIZE))
  eval.registerMetricProviders(FailedMonitorsMetric(tscId = UUID.randomUUID()))

  eval.runEvaluation(ticks)
}
