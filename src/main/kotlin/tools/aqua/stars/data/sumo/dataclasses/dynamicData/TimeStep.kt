/*
 * Copyright 2025-2026 The STARS Coverage Significance Authors
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

package tools.aqua.stars.data.sumo.dataclasses.dynamicData

import java.util.UUID
import tools.aqua.stars.core.types.TickDataType

/**
 * One tick (timestep) of simulation data as a STARS [TickDataType].
 *
 * @param identifier The identifier of the [TimeStep].
 * @property runId Run identifier.
 * @property sourceIdentifier Source identifier.
 * @property mutantId Mutant identifier.
 * @property scenarioConfigId Scenario configuration identifier.
 * @property tickTimeMillis Tick time in milliseconds.
 * @property vehiclesInTick Vehicles present in this tick.
 * @property collisionsInTick Collisions occurring during this tick.
 * @property ego The ego vehicle.
 */
class TimeStep(
    identifier: String,
    val runId: UUID,
    val sourceIdentifier: String,
    val mutantId: UUID?,
    val scenarioConfigId: UUID,
    val tickTimeMillis: Long,
    val vehiclesInTick: List<Vehicle>,
    val collisionsInTick: List<CollisionEvent>,
    override val ego: Vehicle
) :
    TickDataType<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>(
        currentTickUnit = TickUnitMilliseconds(tickTimeMillis),
        entities = LinkedHashSet(vehiclesInTick),
        identifier = identifier) {

  /** Holds a list of all vehicles except the ego vehicle. */
  val nonEgoVehicles: List<Vehicle> = vehiclesInTick.filter { it != ego }

  /**
   * Gets all vehicles except the given one.
   *
   * @param vehicle Vehicle to exclude.
   * @return List of vehicles except the given one.
   */
  fun getOtherVehicles(vehicle: Vehicle): List<Vehicle> = vehiclesInTick.filter { it != vehicle }

  /**
   * Gets a vehicle by its id.
   *
   * @param vehicleId Vehicle id.
   * @return Vehicle with the given id.
   * @throws IllegalArgumentException If no vehicle with the given id exists.
   */
  fun getVehicleById(vehicleId: String): Vehicle =
      vehiclesInTick.firstOrNull { it.vehicleId == vehicleId }
          ?: error("No vehicle with id $vehicleId")

  override fun toString(): String = sourceIdentifier
}
