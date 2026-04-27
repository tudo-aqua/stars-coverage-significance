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
import org.jetbrains.exposed.sql.SortOrder
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.TotalTickDifferenceMetric
import tools.aqua.stars.coverage.significance.BUFFER_SIZE
import tools.aqua.stars.coverage.significance.MAX_LENGTH_OF_SCENARIO_IN_SECONDS
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.significance.metrics.FailedMonitorsPerTickMetric
import tools.aqua.stars.coverage.significance.tsc
import tools.aqua.stars.coverage.significance.utils.TSCTikzRenderer
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
  val tscTTC = tsc()
  val tscTikz = TSCTikzRenderer.render(tscTTC)

  DbBootstrap.connect()
  val libsumoDynamicDataCollector = LibsumoMutantDataCollector()

  val runId = UUID.randomUUID()
  val mutantIds =
      setOf(
          UUID.fromString("e564db3a-0e52-4c1a-b002-b841502c7eec"),
          //          UUID.fromString("9f39fa7b-c68f-4197-a27e-de7b57fc967b"),
          //          UUID.fromString("b32511f8-4e4f-48a7-b50d-c66ccd67b475"),
      )

  val scenarioIds = db {
    MetricFailedMonitorsRepository.getAll()
        .orderBy(MetricFailedMonitorsTable.monitorG0Failed, SortOrder.DESC)
        .orderBy(MetricFailedMonitorsTable.startingScenarioConfiguration, SortOrder.ASC)
        .limit(100)
        .map { it[MetricFailedMonitorsTable.startingScenarioConfiguration].value }
        .distinct()
      .toMutableList()
  }
  scenarioIds += UUID.fromString("0e9b71df-ad81-4881-aaae-7a00e272f60e")

  //  val scenarioIds = setOf(UUID.fromString("1dc5716f-5f9e-42c6-a11d-75d38c6fcd7b"))

  val tickSequences = mutableListOf<TickSequence<TimeStep>>()

  mutantIds.forEach { mutantId ->
    val mutantEntry =
        checkNotNull(MutantsRepository.getById(mutantId)) {
          "Mutant with id $mutantId not found in database. Please make sure to insert a mutant with this id before running the manual testing main function."
        }
    val mutant = AutopilotMutants.create(mutantEntry.mutantNumber)

    scenarioIds.forEach { scenarioId ->
      val scenario = ScenarioStartingConfigurationRepository.getById(scenarioId)
      checkNotNull(scenario) {
        "Scenario with id $scenarioId not found in database. Please make sure to insert a scenario with this id before running the manual testing main function."
      }

      //    val manualScenario =
      //        GeneratedScenario(
      //                spawns =
      //                    listOf(
      //                        Spawn(
      //                            row = MIDDLE_ROW,
      //                            lane = RIGHT_LANE,
      //                            positionMeters = 100.0f,
      //                            type = GridVehicleType.EGO),
      //                        Spawn(
      //                            row = MIDDLE_ROW,
      //                            lane = CENTER_LANE,
      //                            positionMeters = 100.0f,
      //                            type = GridVehicleType.CALM),
      //                        Spawn(
      //                            row = TOP_ROW,
      //                            lane = RIGHT_LANE,
      //                            positionMeters = 130.0f,
      //                            type = GridVehicleType.CALM),
      //                    ))
      //            .toScenarioStartingConfigurationEntry(id = UUID.randomUUID())

      val libSumoTicks =
          libsumoDynamicDataCollector.runGeneratedScenario(
              runId = runId,
              scenario = scenario,
              mutant = mutant,
              mutantId = mutantId,
              writeFCDReplayFile = true)
      val ticksUntilFirstAccident = libSumoTicks.takeUntilFirstAccident()

      tickSequences.add(
          ticksUntilFirstAccident.asTickSequence(
              scenarioId.toString(),
              bufferSize = BUFFER_SIZE,
              iterationOrder = TickSequence.IterationOrder.BACKWARD,
              iterationMode = TickSequence.IterationMode.END_FILLED))
    }
  }

  println(
      """
    Inside /sumoData/fcdReplay:
    python "C:\Program Files (x86)\Eclipse\Sumo\tools\fcdReplay.py" -k fcdReplay.sumocfg -f fcdReplay.fcd.xml
  """
          .trimIndent())

  val staticTsc = tsc()

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

  val failedMonitorsMetric =
      FailedMonitorsPerTickMetric(
          writeToDb = false, writeVehicleStateImages = true, writeVehicleStateVideo = true)

  eval.registerMetricProviders(failedMonitorsMetric, totalTickDifferenceMetric)

  eval.runEvaluation(tickSequences.asSequence())
}

private fun List<TimeStep>.takeUntilFirstAccident(): List<TimeStep> {
  val firstAccidentIndex = indexOfFirst { it.collisionsInTick.isNotEmpty() }
  return if (firstAccidentIndex == -1) this else take(firstAccidentIndex + 1)
}
