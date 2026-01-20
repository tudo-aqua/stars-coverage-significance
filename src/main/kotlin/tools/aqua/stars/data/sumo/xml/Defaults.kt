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

package tools.aqua.stars.data.sumo.xml

import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.routeData.VehicleTypeDefinition
import tools.aqua.stars.data.sumo.dataclasses.staticData.BoundaryBox
import tools.aqua.stars.data.sumo.dataclasses.staticData.Edge
import tools.aqua.stars.data.sumo.dataclasses.staticData.Junction
import tools.aqua.stars.data.sumo.dataclasses.staticData.JunctionType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.Location
import tools.aqua.stars.data.sumo.dataclasses.staticData.Point
import tools.aqua.stars.data.sumo.dataclasses.staticData.Projection

/** Non-null placeholder objects used when SUMO output omits or references unknown ids. */
object Defaults {

  /** Placeholder location used when no location data is available. */
  val unknownLocation =
      Location(
          netOffset = Point(0.0f, 0.0f),
          convertedBoundary = BoundaryBox(0.0, 0.0, 0.0, 0.0),
          originalBoundary = BoundaryBox(0.0, 0.0, 0.0, 0.0),
          projection = Projection.None)

  /** Placeholder junction used for internal/unknown edges. */
  val unknownJunction: Junction =
      Junction(
          junctionId = "UNKNOWN_JUNCTION",
          junctionType = JunctionType.UNKNOWN,
          location = Point(0.0f, 0.0f),
          shape = emptyList())

  /** Placeholder edge used when an edge id cannot be resolved. */
  val unknownEdge: Edge =
      Edge(
          edgeId = "UNKNOWN_EDGE",
          fromJunction = unknownJunction,
          toJunction = unknownJunction,
          edgeFunction = "",
          edgePriority = 0)

  /** Placeholder lane used when a lane id cannot be resolved. */
  val unknownLane: Lane =
      Lane(
          laneId = "UNKNOWN_LANE",
          laneIndex = 0,
          speedLimitMetersPerSecond = 0.0f,
          laneLengthMeters = 0.0f,
          laneShape = emptyList(),
          parentEdge = unknownEdge)

  /** Placeholder vehicle type used when a vehicle's type cannot be resolved. */
  val unknownVehicleTypeDefinition =
      VehicleTypeDefinition(
          typeId = "UNKNOWN_TYPE",
          vehicleClass = "",
          minGapMeters = 0.0,
          tauSeconds = 0.0,
          parameters = emptyList(),
          rawAttributes = emptyMap())

  /** Placeholder vehicle type used when a vehicle's type cannot be resolved. */
  val unknownVehicleType = VehicleType(unknownVehicleTypeDefinition)
}
