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

import tools.aqua.stars.core.types.EntityType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Edge
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane

/**
 * A vehicle entity in STARS terms.
 *
 * @property vehicleId SUMO vehicle id.
 * @property vehicleType Reference to inferred/known vehicle type.
 * @property currentLane Lane pointer the vehicle is on.
 * @property currentEdge Edge pointer the vehicle is on.
 * @property positionOnLaneMeters Position on lane (m).
 * @property speedMetersPerSecond Speed (m/s).
 */
data class Vehicle(
    val vehicleId: String,
    val vehicleType: VehicleType,
    val currentLane: Lane,
    val currentEdge: Edge,
    val positionOnLaneMeters: Float,
    val speedMetersPerSecond: Float
) : EntityType<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>() {

  /** Speed in km/h. */
  val speedKmPerHour: Float = speedMetersPerSecond * 3.6f

  /** Tracks vehicles across ticks by id only. */
  override fun equals(other: Any?): Boolean = other is Vehicle && other.vehicleId == this.vehicleId

  /** Tracks vehicles across ticks by id only. */
  override fun hashCode(): Int = vehicleId.hashCode()
}
