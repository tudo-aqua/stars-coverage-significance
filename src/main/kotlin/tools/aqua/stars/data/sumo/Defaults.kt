/*
 * Copyright 2025 The STARS Coverage Significance Authors
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

package tools.aqua.stars.data.sumo

import tools.aqua.stars.data.sumo.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.staticData.Edge
import tools.aqua.stars.data.sumo.staticData.Lane

/** Non-null placeholder objects used when SUMO output omits or references unknown ids. */
object Defaults {

  /** Placeholder lane used when a lane id cannot be resolved. */
  val unknownLane: Lane =
      Lane(
          laneId = "UNKNOWN_LANE",
          laneIndex = 0,
          speedLimitMetersPerSecond = 0.0f,
          laneLengthMeters = 0.0f,
          laneShape = emptyList())

  /** Placeholder edge used when an edge id cannot be resolved. */
  val unknownEdge: Edge =
      Edge(
          edgeId = "UNKNOWN_EDGE",
          fromJunctionId = "",
          toJunctionId = "",
          edgeFunction = "",
          edgePriority = 0,
          lanes = emptyList())

  /** Placeholder type used when vehicle type inference fails. */
  val unknownVehicleType: VehicleType = VehicleType(typeId = "UNKNOWN_TYPE")
}
