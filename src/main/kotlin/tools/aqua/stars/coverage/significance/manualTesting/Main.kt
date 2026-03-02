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
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.TotalTickDifferenceMetric
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.MAX_LENGTH_OF_SCENARIO_IN_SECONDS
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsMetric
import tools.aqua.stars.coverage.significance.smallStaticTsc
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.sumo.Mutant200kmh

/**
 * This is a manual testing utility to run a single scenario and collect the dynamic data using
 * libsumo, then evaluate it using the TSCEvaluation. It can be used to quickly test changes to the
 * evaluation logic or metrics without running the entire evaluation pipeline.
 */
fun main() {
  val libsumoDynamicDataCollector = LibsumoMutantDataCollector()

  val runId = UUID.randomUUID()
  val mutantId = UUID.randomUUID()
  val scenarioId = UUID.randomUUID()

  val scenario =
      GeneratedScenario(
          spawns =
              listOf(
                  Spawn(
                      row = MIDDLE_ROW,
                      lane = RIGHT_LANE,
                      positionMeters = 100.0f,
                      type = GridVehicleType.EGO),
                  Spawn(
                      row = MIDDLE_ROW,
                      lane = CENTER_LANE,
                      positionMeters = 100.0f,
                      type = GridVehicleType.CALM),
                  Spawn(
                      row = TOP_ROW,
                      lane = RIGHT_LANE,
                      positionMeters = 130.0f,
                      type = GridVehicleType.CALM),
              ))

  val libSumoTicks =
      libsumoDynamicDataCollector.runGeneratedScenario(
          runId = runId,
          scenario = scenario.toScenarioStartingConfigurationEntry(scenarioId),
          mutant = Mutant200kmh(),
          mutantId = mutantId)
  val tickSequences = mutableListOf<TickSequence<TimeStep>>()

  tickSequences.add(
      libSumoTicks.asTickSequence(
          scenario.id,
          bufferSize = BUFFER_SIZE,
          iterationOrder = TickSequence.IterationOrder.BACKWARD,
          iterationMode = TickSequence.IterationMode.END_FILLED))

  println(
      """
    Inside /sumoData/fcdReplay:
    python "C:\Program Files (x86)\Eclipse\Sumo\tools\fcdReplay.py" -k fcdReplay.sumocfg -f fcdReplay.fcd.xml
  """
          .trimIndent())
  val staticTsc = smallStaticTsc()

  val eval =
      TSCEvaluation(
          staticTsc,
          writePlots = false,
          writePlotDataCSV = false,
          writeSerializedResults = false,
          compareToPreviousRun = false)

  eval.clearHooks()
  eval.registerPreTickEvaluationHooks(
      MinTicksPerTickSequenceHook(2),
      MaxSecondsEvaluationHook(maxSeconds = MAX_LENGTH_OF_SCENARIO_IN_SECONDS.toInt()))

  val totalTickDifferenceMetric =
      TotalTickDifferenceMetric<
          Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>()

  eval.registerMetricProviders(
      FailedMonitorsMetric(tscId = UUID.fromString("d5b2234a-726b-41c9-a3a8-fd414ab6064b")),
      totalTickDifferenceMetric)

  eval.runEvaluation(tickSequences.asSequence())
}
