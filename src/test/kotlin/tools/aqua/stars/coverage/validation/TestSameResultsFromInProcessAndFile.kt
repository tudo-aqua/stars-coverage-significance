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

package tools.aqua.stars.coverage.validation

import java.util.UUID
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import tools.aqua.stars.coverage.significance.COLLISION_DIR
import tools.aqua.stars.coverage.significance.EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.EXPORT_DIR
import tools.aqua.stars.coverage.significance.SCENARIO_DIR
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.generateGridTrafficScenarios
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.seedGridTrafficScenarios
import tools.aqua.stars.coverage.significance.parallelism
import tools.aqua.stars.coverage.significance.sumo.runSumoForScenariosParallel
import tools.aqua.stars.coverage.significance.utils.listSortedFiles
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector
import tools.aqua.stars.data.sumo.xml.SumoImporter

/**
 * Test to ensure that running generated scenarios with libsumo in-process and via exported XML
 * files yields the same results.
 */
class TestSameResultsFromInProcessAndFile {

  /**
   * Test same results when running generated scenarios with libsumo in-process and via exported XML
   * files.
   */
  @Test
  fun `Test same results when running generated scenarios with libsumo in-process and via exported XML files`() {
    val numberOfScenarios = 1
    for (i in 0..50) {
      generateGridTrafficScenarios(
          n = numberOfScenarios, seed = i, insertIntoDatabase = false, cleanGenerationFiles = true)

      val scenarioFiles = listSortedFiles(SCENARIO_DIR)
      runSumoForScenariosParallel(
          scenarioFiles = scenarioFiles, parallelism = parallelism, writeCfgFiles = true)

      val scenarioFile = scenarioFiles.first()

      val exportFile = listSortedFiles(EXPORT_DIR).first()
      val collisionFile = listSortedFiles(COLLISION_DIR).first()
      val xmlSumoTicks =
          SumoImporter.loadTicksAsList(
              scenarioFile = scenarioFile,
              exportFile = exportFile,
              collisionFile = collisionFile,
              netFilePath = Path("$EXPERIMENT_DIR/grid_highway.net.xml"),
              vehicleTypesAdditionalFilePath = Path("$EXPERIMENT_DIR/vTypes.add.xml"))

      val scenarios =
          seedGridTrafficScenarios(n = numberOfScenarios, seed = i, insertIntoDatabase = false)
      assert(scenarios.size == numberOfScenarios) {
        "Expected $numberOfScenarios scenarios. Got ${scenarios.size}."
      }

      val libsumoDynamicDataCollector = LibsumoDynamicDataCollector()

      val libSumoTicks =
          libsumoDynamicDataCollector.runGeneratedScenario(
              runId = UUID.randomUUID(),
              scenario = scenarios.first().toScenarioStartingConfigurationEntry(UUID.randomUUID()),
              mutantId = null)

      assert(libSumoTicks.size == xmlSumoTicks.size) { "Size of the generated scenarios differ." }

      libSumoTicks.forEachIndexed { index, libSumoTick ->
        val xmlSumoTick = xmlSumoTicks[index]

        val libSumoVehicles = libSumoTick.vehiclesInTick
        val xmlSumoVehicles = xmlSumoTick.vehiclesInTick

        assert(xmlSumoVehicles.size == libSumoVehicles.size) {
          "Number of vehicles in tick ${index + 1} differs."
        }
        assert(xmlSumoTick.collisionsInTick.size == libSumoTick.collisionsInTick.size) {
          "Number of collisions in tick ${index + 1} differs."
        }

        libSumoVehicles.forEach { libSumoVehicle ->
          val xmlSumoVehicle =
              xmlSumoVehicles.firstOrNull { it.vehicleId == libSumoVehicle.vehicleId }
          assertNotNull(xmlSumoVehicle) { "Vehicle ${libSumoVehicle.vehicleId} not found in XML." }
          assert(
              abs(libSumoVehicle.positionOnLaneMeters - xmlSumoVehicle.positionOnLaneMeters) <
                  0.01) {
                "Vehicle ${libSumoVehicle.vehicleId} has different positions in XML and generated scenario (${libSumoVehicle.positionOnLaneMeters} vs ${xmlSumoVehicle.positionOnLaneMeters})"
              }
          assert(
              abs(libSumoVehicle.speedMetersPerSecond - xmlSumoVehicle.speedMetersPerSecond) <
                  0.01) {
                "Vehicle ${libSumoVehicle.vehicleId} has different speeds in XML and generated scenario (${libSumoVehicle.speedMetersPerSecond} vs ${xmlSumoVehicle.speedMetersPerSecond})."
              }
          assert(libSumoVehicle.vehicleType == xmlSumoVehicle.vehicleType) {
            "Vehicle ${libSumoVehicle.vehicleId} has different types in XML and generated scenario (${libSumoVehicle.vehicleType} vs ${xmlSumoVehicle.vehicleType})"
          }
          assert(libSumoVehicle.currentEdge.edgeId == xmlSumoVehicle.currentEdge.edgeId) {
            "Vehicle ${libSumoVehicle.vehicleId} has different edges in XML and generated scenario (${libSumoVehicle.currentEdge.edgeId} vs ${xmlSumoVehicle.currentEdge.edgeId})"
          }
        }
      }
    }
  }
}
