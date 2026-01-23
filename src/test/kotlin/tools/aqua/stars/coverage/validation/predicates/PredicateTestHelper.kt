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

import tools.aqua.stars.coverage.significance.isOnLeftLane
import tools.aqua.stars.coverage.significance.isOnLeftLaneOf
import tools.aqua.stars.coverage.significance.isOnMiddleLane
import tools.aqua.stars.coverage.significance.isOnRightLane
import tools.aqua.stars.coverage.significance.isOnRightLaneOf
import tools.aqua.stars.coverage.significance.isOnSameLane
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneInFrontIsFaster
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneInFrontIsSlower
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneInFrontSameSpeed
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.routeData.VehicleTypeDefinition
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane

object PredicateTestHelper {
  val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()

  val allLanePredicates = listOf(isOnLeftLane, isOnMiddleLane, isOnRightLane)
  val allRelativePositionPredicates = listOf(isOnSameLane, isOnLeftLaneOf, isOnRightLaneOf)
  val allSpeedPredicatesSameLaneInFront =
      listOf(
          vehicleOnSameLaneInFrontIsFaster,
          vehicleOnSameLaneInFrontIsSlower,
          vehicleOnSameLaneInFrontSameSpeed)

  fun getDefaultVehicleType(): VehicleType =
      VehicleType(
          VehicleTypeDefinition(
              typeId = "default",
              vehicleClass = "car",
              minGapMeters = 50.0,
              tauSeconds = 5.0,
              parameters = emptyList(),
              rawAttributes = emptyMap()))

  fun getTestTimeStep(
      vehicles: List<Vehicle> = listOf(getTestVehicle()),
      ego: Vehicle = vehicles.first()
  ): TimeStep =
      TimeStep(
          identifier = "tick-0",
          sourceIdentifier = "Test",
          mutantId = null,
          tickTimeMillis = 100,
          vehiclesInTick = vehicles,
          collisionsInTick = emptyList(),
          ego = ego)

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
          speedMetersPerSecond = speedKmH / 3.6f)
}
