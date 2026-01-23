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

package tools.aqua.stars.coverage.validation.predicates

import java.io.File
import kotlin.io.path.Path
import tools.aqua.stars.core.validation.ManualLabelTests
import tools.aqua.stars.core.validation.manuallyLabelledFile
import tools.aqua.stars.coverage.significance.hasVehicleBehindOnSameLane
import tools.aqua.stars.coverage.significance.hasVehicleBesidesOnLeftLane
import tools.aqua.stars.coverage.significance.hasVehicleBesidesOnRightLane
import tools.aqua.stars.coverage.significance.hasVehicleInBehindOnLeftLane
import tools.aqua.stars.coverage.significance.hasVehicleInFrontOnLeftLane
import tools.aqua.stars.coverage.significance.hasVehicleInFrontOnSameLane
import tools.aqua.stars.coverage.significance.isOnMiddleLane
import tools.aqua.stars.coverage.significance.vehicleOnLeftLaneBehindSameSpeed
import tools.aqua.stars.coverage.significance.vehicleOnLeftLaneBesideIsSlower
import tools.aqua.stars.coverage.significance.vehicleOnLeftLaneInFrontIsSlower
import tools.aqua.stars.coverage.significance.vehicleOnRightLaneBesideIsFaster
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneBehindIsSlower
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneInFrontIsSlower
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.xml.SumoImporter

/** Test class for manually labelled tests for one specific SUMO scenario. */
class ManuallyLabelledTests :
    ManualLabelTests<TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>() {
  override val manualLabelTestFiles = listOf(manuallyLabelledTests)
}

/** Path to the SUMO network. */
val sumoNetworkFile = Path("src/test/resources/grid_highway.net.xml")
/** Path to the additional vehicle types file. */
val sumoVTypesFile = Path("src/test/resources/vTypes.add.xml")
/** Paths to the SUMO scenario file. */
val sumoScenarioFile =
    File(
        "src/test/resources/r0l1c0150__r0l2n0150__r1l0s1050__r1l1e1050__r1l2c1050__r2l1c1950__r2l2c1950.rou.xml")
/** Paths to the SUMO export file. */
val sumoExportFile =
    File(
        "src/test/resources/r0l1c0150__r0l2n0150__r1l0s1050__r1l1e1050__r1l2c1050__r2l1c1950__r2l2c1950.export.xml")
/** Paths to the SUMO collisions file. */
val sumoCollisionFile =
    File(
        "src/test/resources/r0l1c0150__r0l2n0150__r1l0s1050__r1l1e1050__r1l2c1050__r2l1c1950__r2l2c1950.collisions.xml")

/** Manually labeled tests for SUMO scenario. */
val manuallyLabelledTests =
    manuallyLabelledFile(
        ticksToTest =
            SumoImporter.loadTicksAsList(
                scenarioFile = sumoScenarioFile,
                exportFile = sumoExportFile,
                collisionFile = sumoCollisionFile,
                netFilePath = sumoNetworkFile,
                vehicleTypesAdditionalFilePath = sumoVTypesFile)) {
          predicateHolds(isOnMiddleLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // region Front Vehicle Predicate
          predicateHolds(hasVehicleInFrontOnSameLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnSameLaneInFrontIsSlower) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
          // region Vehicle Behind Predicate
          predicateHolds(hasVehicleBehindOnSameLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnSameLaneBehindIsSlower) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
          // region Vehicle Besides Left Predicate
          predicateHolds(hasVehicleBesidesOnLeftLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnLeftLaneBesideIsSlower) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
          // region Vehicle Besides Left Front Predicate
          predicateHolds(hasVehicleInFrontOnLeftLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnLeftLaneInFrontIsSlower) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
          // region Vehicle Besides Left Behind Predicate
          predicateHolds(hasVehicleInBehindOnLeftLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnLeftLaneBehindSameSpeed) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
          // region Vehicle Besides Right Predicate
          predicateHolds(hasVehicleBesidesOnRightLane) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          predicateHolds(vehicleOnRightLaneBesideIsFaster) {
            interval(TickUnitMilliseconds(0), TickUnitMilliseconds(100))
          }
          // endregion
        }
