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

import java.util.UUID
import tools.aqua.stars.coverage.significance.tsc.isOnLeftLane
import tools.aqua.stars.coverage.significance.tsc.isOnLeftLaneOf
import tools.aqua.stars.coverage.significance.tsc.isOnMiddleLane
import tools.aqua.stars.coverage.significance.tsc.isOnRightLane
import tools.aqua.stars.coverage.significance.tsc.isOnRightLaneOf
import tools.aqua.stars.coverage.significance.tsc.isOnSameLane
import tools.aqua.stars.coverage.significance.tsc.vehicleOnSameLaneInFrontIsFaster
import tools.aqua.stars.coverage.significance.tsc.vehicleOnSameLaneInFrontIsSlower
import tools.aqua.stars.coverage.significance.tsc.vehicleOnSameLaneInFrontSameSpeed
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.routeData.VehicleTypeDefinition
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane

/** Helper object for predicate tests. */
object PredicateTestHelper {
  /** Test road network with three lanes and a single edge. */
  val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()

  /** All lane predicates. */
  val allLanePredicates = listOf(isOnLeftLane, isOnMiddleLane, isOnRightLane)
  /** All relative position predicates. */
  val allRelativePositionPredicates = listOf(isOnSameLane, isOnLeftLaneOf, isOnRightLaneOf)
  /** All speed predicates for vehicles on the same lane in front of each other. */
  val allSpeedPredicatesSameLaneInFront =
      listOf(
          vehicleOnSameLaneInFrontIsFaster,
          vehicleOnSameLaneInFrontIsSlower,
          vehicleOnSameLaneInFrontSameSpeed)

  /** Default vehicle type for test vehicles. */
  fun getDefaultVehicleType(): VehicleType =
      VehicleType(
          VehicleTypeDefinition(
              typeId = "default",
              vehicleClass = "car",
              minGapMeters = 50.0,
              tauSeconds = 5.0,
              parameters = emptyList(),
              rawAttributes = emptyMap()))

  /** Creates a test [TimeStep] with the given parameters. */
  fun getTestTimeStep(
      vehicles: List<Vehicle> = listOf(getTestVehicle()),
      tickTimeMillis: Long = 100L,
      ego: Vehicle = vehicles.first(),
      collisions: List<Pair<Long, Pair<Vehicle, Vehicle>>> = emptyList()
  ): TimeStep =
      TimeStep(
          identifier = "tick-0",
          sourceIdentifier = "Test",
          mutantId = UUID.randomUUID(),
          tickTimeMillis = tickTimeMillis,
          vehiclesInTick = vehicles,
          collisionsInTick =
              collisions.map { (timeMillis, it) ->
                CollisionEvent(
                    timeMillis.toFloat(),
                    lane = it.first.currentLane,
                    edge = it.first.currentEdge,
                    positionOnLaneMeters = it.first.positionOnLaneMeters,
                    colliderVehicle = it.first,
                    victimVehicle = it.second,
                    collisionType = "",
                    rawAttributes = emptyMap())
              },
          ego = ego,
          runId = UUID.randomUUID(),
          scenarioConfigId = UUID.randomUUID(),
          egoManeuver = null)

  /** Creates a test [Vehicle] with the given parameters. */
  fun getTestVehicle(
      vehicleId: String = "vehicle1",
      lane: Lane = roadNetwork.middleLane,
      positionInMeters: Float = 50.0f,
      speedKmH: Float = 10.0f
  ): Vehicle =
      Vehicle(
          vehicleId = vehicleId,
          vehicleType = getDefaultVehicleType(),
          currentLane = lane,
          currentEdge = roadNetwork.singleEdge,
          positionOnLaneMeters = positionInMeters,
          speedMetersPerSecond = speedKmH / 3.6f,
          accelerationMetersPerSecondSquared = -1.0f,
          frontBumperPositionOnLaneMeters = positionInMeters,
          backBumperPositionOnLaneMeters = positionInMeters - 2.0f)
}
