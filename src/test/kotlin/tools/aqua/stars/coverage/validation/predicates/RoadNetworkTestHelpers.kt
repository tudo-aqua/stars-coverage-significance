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

import tools.aqua.stars.data.sumo.dataclasses.staticData.BoundaryBox
import tools.aqua.stars.data.sumo.dataclasses.staticData.Edge
import tools.aqua.stars.data.sumo.dataclasses.staticData.Junction
import tools.aqua.stars.data.sumo.dataclasses.staticData.JunctionType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.Location
import tools.aqua.stars.data.sumo.dataclasses.staticData.Point
import tools.aqua.stars.data.sumo.dataclasses.staticData.Projection
import tools.aqua.stars.data.sumo.dataclasses.staticData.RoadNetwork

/**
 * Test helpers for building small in-memory road networks.
 *
 * The helpers here intentionally create object graphs with proper pointers:
 * - [Edge] contains pointers to [Junction]s and holds lane objects in [Edge.lanes].
 * - [Lane] stores a pointer to its owning [Edge] in [Lane.parentEdge].
 *
 * The lane indices match the constants used by predicates: RIGHT = 0, MIDDLE = 1, LEFT = 2.
 */
object RoadNetworkTestHelpers {

  /** Lane index constant for the right lane (index 0). */
  const val LANE_INDEX_RIGHT: Int = 0
  /** Lane index constant for the middle lane (index 1). */
  const val LANE_INDEX_MIDDLE: Int = 1
  /** Lane index constant for the left lane (index 2). */
  const val LANE_INDEX_LEFT: Int = 2

  /**
   * Creates a minimal [RoadNetwork] with exactly one directed [Edge] and exactly three lanes: left
   * (index 2), middle (index 1), right (index 0).
   *
   * The generated geometry is straight and simplistic; tests should only rely on consistent ids and
   * indices.
   *
   * @param edgeId Edge id.
   * @param fromJunctionId Source junction id.
   * @param toJunctionId Target junction id.
   * @param laneLengthMeters Lane length for all three lanes.
   * @param speedLimitMetersPerSecond Speed limit for all three lanes.
   * @return A [RoadNetwork] bundle with easy accessors.
   */
  fun threeLaneSingleEdgeNetwork(
      edgeId: String = "E0",
      fromJunctionId: String = "J0",
      toJunctionId: String = "J1",
      laneLengthMeters: Float = 100.0f,
      speedLimitMetersPerSecond: Float = 13.89f, // 50 km/h
  ): RoadNetwork {
    // Minimal, consistent geometry (a straight edge).
    val fromPoint = Point(0.0f, 0.0f)
    val toPoint = Point(laneLengthMeters, 0.0f)
    val shape = listOf(fromPoint, toPoint)

    val fromJunction =
        Junction(
            junctionId = fromJunctionId,
            junctionType = JunctionType.PRIORITY,
            location = fromPoint,
            shape = listOf(fromPoint),
        )

    val toJunction =
        Junction(
            junctionId = toJunctionId,
            junctionType = JunctionType.PRIORITY,
            location = toPoint,
            shape = listOf(toPoint),
        )

    // Create edge first (lanes are added afterwards to allow lane.parentEdge pointers).
    val edge =
        Edge(
            edgeId = edgeId,
            fromJunction = fromJunction,
            toJunction = toJunction,
            edgeFunction = "",
            edgePriority = 0,
        )

    // Create 3 lanes with the expected indices and attach them to the edge.
    val rightLane =
        Lane(
            laneId = "${edgeId}_$LANE_INDEX_RIGHT",
            laneIndex = LANE_INDEX_RIGHT,
            speedLimitMetersPerSecond = speedLimitMetersPerSecond,
            laneLengthMeters = laneLengthMeters,
            laneShape = shape,
            parentEdge = edge,
        )

    val middleLane =
        Lane(
            laneId = "${edgeId}_$LANE_INDEX_MIDDLE",
            laneIndex = LANE_INDEX_MIDDLE,
            speedLimitMetersPerSecond = speedLimitMetersPerSecond,
            laneLengthMeters = laneLengthMeters,
            laneShape = shape,
            parentEdge = edge,
        )

    val leftLane =
        Lane(
            laneId = "${edgeId}_$LANE_INDEX_LEFT",
            laneIndex = LANE_INDEX_LEFT,
            speedLimitMetersPerSecond = speedLimitMetersPerSecond,
            laneLengthMeters = laneLengthMeters,
            laneShape = shape,
            parentEdge = edge,
        )

    edge.lanes.addAll(listOf(rightLane, middleLane, leftLane))

    // Populate minimal incoming lanes on the target junction (useful for algorithms that traverse
    // junctions).
    toJunction.incomingLanes.addAll(edge.lanes)

    // Minimal location. Adjust if your BoundaryBox differs.
    val location =
        Location(
            netOffset = Point(0.0f, 0.0f),
            convertedBoundary = BoundaryBox(0.0, 0.0, laneLengthMeters.toDouble(), 1.0),
            originalBoundary = BoundaryBox(0.0, 0.0, laneLengthMeters.toDouble(), 1.0),
            projection = Projection.None,
        )

    val roadNetwork =
        RoadNetwork(
            location = location,
            junctions = listOf(fromJunction, toJunction),
            edges = listOf(edge),
            connections = emptyList(),
        )
    return roadNetwork
  }
}

/** Gets the single [Edge] of a single-edge [RoadNetwork]. */
val RoadNetwork.singleEdge: Edge
  get() = edges.single()

/** Gets the left lane (index 2) of a three-lane [RoadNetwork]. */
val RoadNetwork.leftLane: Lane
  get() = lanes.single { it.laneIndex == RoadNetworkTestHelpers.LANE_INDEX_LEFT }

/** Gets the middle lane (index 1) of a three-lane [RoadNetwork]. */
val RoadNetwork.middleLane: Lane
  get() = lanes.single { it.laneIndex == RoadNetworkTestHelpers.LANE_INDEX_MIDDLE }

/** Gets the right lane (index 0) of a three-lane [RoadNetwork]. */
val RoadNetwork.rightLane: Lane
  get() = lanes.single { it.laneIndex == RoadNetworkTestHelpers.LANE_INDEX_RIGHT }
