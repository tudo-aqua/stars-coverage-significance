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
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsMetric
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.coverage.significance.smallStaticTsc
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.sumo.mutants.AutopilotMutants

/**
 * This is a manual testing utility to run a single scenario and collect the dynamic data using
 * libsumo, then evaluate it using the TSCEvaluation. It can be used to quickly test changes to the
 * evaluation logic or metrics without running the entire evaluation pipeline.
 */
fun main() {
  DbBootstrap.connect()
  val libsumoDynamicDataCollector = LibsumoMutantDataCollector()

  val runId = UUID.randomUUID()
  val tscId = UUID.fromString("d5b2234a-726b-41c9-a3a8-fd414ab6064b")
  val mutantId = UUID.fromString("516b92f5-45e8-4100-81ed-bbc199659a90")

  val listOfScenarios =
      listOf(
          "ca3d5fc7-bd46-4769-8f90-2c3cf6384c27",
      )
  listOfScenarios.forEach { scenarioId ->
    val scenarioId = UUID.fromString(scenarioId)

    val scenario = ScenarioStartingConfigurationRepository.getById(scenarioId)
    checkNotNull(scenario) {
      "Scenario with id $scenarioId not found in database. Please make sure to insert a scenario with this id before running the manual testing main function."
    }
    val mutantEntry = checkNotNull(MutantsRepository.getById(mutantId))
    val mutant = AutopilotMutants.create(mutantEntry.mutantNumber)

    val manualScenario =
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
            .toScenarioStartingConfigurationEntry(id = UUID.randomUUID())

    val libSumoTicks =
        libsumoDynamicDataCollector.runGeneratedScenario(
            runId = runId,
            scenario = scenario,
            mutant = mutant,
            mutantId = mutantId,
            writeFCDReplayFile = true)
    val tickSequences = mutableListOf<TickSequence<TimeStep>>()

    tickSequences.add(
        libSumoTicks.asTickSequence(
            scenario.humanReadableScenarioId,
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

    val failedMonitorsMetric = FailedMonitorsMetric(tscId = tscId, writeToDb = false)

    eval.registerMetricProviders(
        failedMonitorsMetric, totalTickDifferenceMetric, StartingValidTSCInstancesPerTSCMetric())

    eval.runEvaluation(tickSequences.asSequence())
  }
}
