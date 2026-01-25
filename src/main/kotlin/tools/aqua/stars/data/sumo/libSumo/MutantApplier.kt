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

package tools.aqua.stars.data.sumo.libSumo

import org.eclipse.sumo.libsumo.Vehicle
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry

/** Object responsible for applying mutant parameters to the ego vehicle in a SUMO simulation. */
object MutantApplier {

  /**
   * Applies the parameters from the given [MutantEntry] to the ego vehicle identified by
   * [egoVehId].
   *
   * @param egoVehId The ID of the ego vehicle in the SUMO simulation.
   * @param m The [MutantEntry] containing the parameters to apply.
   */
  fun applyToEgoVehicle(egoVehId: String, m: MutantEntry) {
    Vehicle.setSpeedFactor(egoVehId, m.speedFactor)

    // Lane change model attributes
    Vehicle.setParameter(egoVehId, "laneChangeModel.lcAssertive", m.lcAssertive.toString())
    Vehicle.setParameter(egoVehId, "laneChangeModel.lcSpeedGain", m.lcSpeedGain.toString())
    Vehicle.setParameter(egoVehId, "laneChangeModel.lcCooperative", m.lcCooperative.toString())

    // Driver-state device parameters
    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.headwayErrorCoefficient",
        m.headwayErrorCoefficient.toString())
    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.speedDifferenceErrorCoefficient",
        m.speedDifferenceErrorCoefficient.toString())

    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.headwayChangePerceptionThreshold",
        m.headwayChangePerceptionThreshold.toString())
    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.speedDifferenceChangePerceptionThreshold",
        m.speedDifferenceChangePerceptionThreshold.toString())
    Vehicle.setParameter(
        egoVehId, "device.driverstate.maximalReactionTime", m.maximalReactionTime.toString())

    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.errorNoiseIntensityCoefficient",
        m.errorNoiseIntensityCoefficient.toString())
    Vehicle.setParameter(
        egoVehId,
        "device.driverstate.errorTimeScaleCoefficient",
        m.errorTimeScaleCoefficient.toString())

    Vehicle.setParameter(
        egoVehId, "device.driverstate.initialAwareness", m.initialAwareness.toString())
    Vehicle.setParameter(egoVehId, "device.driverstate.minAwareness", m.minAwareness.toString())
  }
}
