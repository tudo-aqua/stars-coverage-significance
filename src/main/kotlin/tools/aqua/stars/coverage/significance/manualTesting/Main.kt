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
          //    "4064c171-bcce-4275-80f9-df9f98391eb6",
          //      "3129ca10-b571-4ca7-afc5-1d589f1a9148",
          //      "875f6634-38b4-4b4b-9ddb-c31c45f15164",
          //      "ebe780b2-3d05-42c9-b7fd-1b86710f29cf",
          //      "31c82f7a-bfb4-483d-ad4c-80466bd97062",
          //      "d451562c-1047-4d48-aad6-8b429c2c8244",
          //      "643a903c-a9b5-4fe0-a77d-d120763b07e4",
          //      "812f4e6d-a077-4cf0-8676-981bfed4936a",
          //      "1eb64b3a-8d46-4851-a246-4c88f8543d62",
          //      "fccef4c7-58d6-43b4-9db2-de9d692311a3",
          //      "ee419ea7-5d1a-4c5e-b35e-9e934b364f06",
          //      "9b8867e2-311d-491f-a235-fc05bbad4fb6",
          //      "f666add7-ffd1-4273-a4d0-6c5dfb061096",
          //      "63c73145-ea8a-405e-91b7-f6da5f1d394a",
          //      "d4e14713-48b2-46ba-99f4-b09a1b93dfa1",
          //      "30fdde34-81ce-4b3a-9a3a-d6cbb432a7fd",
          //      "8ae70203-e7fb-4fca-a5a9-ba248fb7071e",
          //      "cd257d9f-377c-440e-9b37-a43e2053f2ae",
          //      "00299b68-61a4-4c01-bcc3-8b8709ac9f19",
          //      "ca3d5fc7-bd46-4769-8f90-2c3cf6384c27",
          //      "661261b3-ed9b-4d50-b2a1-09df8fea8bac",
          //      "71af424d-341c-426c-a4c1-07271129b408",
          //      "d88c4139-2505-4463-8c93-614d6c07ceeb",
          //      "f32fd134-500b-44b6-a2ab-df37f1282ce0",
          //      "350bcf35-d272-40ab-80e3-460fc332fe52",
          //      "8ecf3f80-2ace-4ad2-bb52-4a01c24480d8",
          //      "d4a0a2dd-8c80-45a4-8aae-1ce19b80ab80",
          //      "35aa1699-3004-41c8-9df3-7384318e412c",
          //      "a87da444-4bdc-4e2f-b1e5-4e85356144eb",
          //      "3949f7f7-f226-4790-bfb8-0e16b03562a2",
          //      "e6d6b14c-15a0-49f7-a5e3-f0b9e0748778",
          //      "bbb78b6b-ff62-4457-9d70-0ad7051b8808",
          //      "15f6946e-f96b-478f-84dc-7b96ba5f3314",
          //      "56432a8f-8c58-49ec-a4ca-e12341c46332",
          "80bf6f70-72db-4457-9856-939bee2e17f1",
          //      "fdc12002-ef60-4741-a1ef-8b126667569f",
          //      "72a729a8-8f89-4ea5-bbe0-195f430c6d0c",
          //      "efefccff-b608-4b54-b3af-6f85ae830162",
          //      "137328d7-e5b8-4c96-8148-e10e3da073e1",
          //      "d1d9789c-c676-438c-8790-ffebb8e87ceb",
          //      "4a75a56f-b7d8-43fd-9647-11c9a2be75c2",
          //      "cc9b3956-9358-4fcf-9dd8-b40d077e5a3c",
          //      "ec3f8acd-64b3-4b52-b27f-b8a6212b7ee1",
          //      "830cc453-00cb-4a7d-8017-5d5fb89d27fc",
          //      "0dea1a12-1de3-4903-9356-aed5e4c4a8d1",
          //      "cdbf3d44-c454-4cff-80ec-ea62041e43ac",
          //      "1e6caa01-3e88-4f78-a13e-7400e92a52d6",
          //      "affd10c1-9f0f-49ec-824a-97a38c03e28d",
          //      "74479f69-fc09-4cb9-95b7-51dc4264fcd6",
          //      "7baf466c-d361-4028-a351-576083d91274",
          //      "fc9fef1a-0369-438c-b4c7-131f8f842624",
          //      "a856c3f2-c8a5-4422-9d14-d92c87c92378",
          //      "35c35721-3710-486f-b42f-e2b7a4e95a98",
          //      "dbcfc5be-3d22-43c2-aae5-1a5ef5378ee4",
          //      "84a71c84-b8fe-4465-95fb-567ab2bfa4a7",
          //      "e9182015-6e65-47f9-a91e-2c51fa75ff7a",
          //      "ee37ea0f-cd90-4086-8cdd-dfe1c020346f",
          //      "5c7efbbf-7904-465e-a9d5-5544a0242bb6",
          //      "ac1216ff-a4b0-491b-a7ea-55704a61e48e",
          //      "ec7c12a7-0f17-4af2-953a-33b697b3bd93",
          //      "a8498f49-49ec-4c90-a215-14f26be49b15",
          //      "58aa982f-a317-4c68-8f99-d87f4121a100",
          //      "28a2a6e8-4eb2-4352-bbbb-c227a30cd619",
          //      "3d9f38a2-00e6-42b0-954c-5111e335121c",
          //      "d5f8321c-545f-473c-8d9a-3cacaec84d9f",
          //      "4e8a737a-2f1c-401f-92c7-c544a520527e",
          //      "fbc117c5-c4b7-4e4a-a615-bd909c7b89fd",
          //      "9a03f66f-bf08-4740-82ee-cd67c74e3939",
          //      "d905f81e-b846-480c-a52b-a87b0d268da3",
          //      "4370322b-6890-4d7b-84f4-c325788aea3e",
          //      "a4812d5e-3280-45a7-bbee-7aca21f452c6",
          //      "a18f59ae-d797-495d-9fbe-80338d2fe8e8",
          //      "af110cd7-aacf-4221-91dc-36c1e309e926",
          //      "8544326c-7f01-44a7-847e-59ef4c5e1d47",
          //      "894ea145-fc83-4722-982b-6e67d74ffdf4",
          //      "de3ce873-0b83-4119-b398-0b02b33fdedb",
          //      "d336f6df-e65c-443e-8d6a-5ea0f1d0ed5e",
          //      "48ca3605-222e-4c97-a720-11b4686335ae",
          //      "87db86f8-beb5-4c05-8903-40a3b6136e63",
          //      "b60e6260-6814-4bda-b283-bf7402dfff9f",
          //      "25ff789f-a678-4413-9345-d100a0b76664",
          //      "a390f227-65ce-4165-9561-a22f6af826d0",
          //      "6ffc1a45-0263-414a-886f-eba5966a0f9e"
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

    val failedMonitorsMetric = FailedMonitorsMetric(tscId = tscId)

    eval.registerMetricProviders(failedMonitorsMetric, totalTickDifferenceMetric)

    eval.runEvaluation(tickSequences.asSequence())
  }
}
