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

import kotlin.test.Test
import tools.aqua.stars.coverage.significance.VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.isBehindOnLeftLane
import tools.aqua.stars.coverage.significance.isInFrontOnLeftLane
import tools.aqua.stars.coverage.significance.isOnLeftLaneOf
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.allRelativePositionPredicates
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

class LeftLanePredicateTest {

  /** Tests the [isOnLeftLaneOf] predicate for left lanes. */
  @Test
  fun `Test vehicles on left lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(isOnLeftLaneOf.holds(tick, vehicle1 to vehicle2))
    allRelativePositionPredicates.minus(isOnLeftLaneOf).forEach { predicate ->
      assert(!predicate.holds(tick, vehicle1 to vehicle2))
    }
  }

  /** Tests the [isOnLeftLaneOf] predicate for different lanes. */
  @Test
  fun `Test vehicles on different lanes`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isOnLeftLaneOf.holds(tick, vehicle1 to vehicle2))
  }

  // region in front
  /** Tests the [isInFrontOnLeftLane] predicate for vehicles in front of each other. */
  @Test
  fun `Test vehicle in front on left lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnLeftLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnLeftLane] predicate for vehicles in front of each other at max distance.
   */
  @Test
  fun `Test vehicle in front on left lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnLeftLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnLeftLane] predicate for vehicles in front of each other at max
   * distance+1.
   */
  @Test
  fun `Test vehicle in front on left lane at max distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO + 1)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isInFrontOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }

  /**
   * Tests the [isInFrontOnLeftLane] predicate for vehicles in front of each other at min distance.
   */
  @Test
  fun `Test vehicle in front on left lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnLeftLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnLeftLane] predicate for vehicles in front of each other at min
   * distance-1.
   */
  @Test
  fun `Test vehicle in front on left lane at min distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)
    val vehicle2 =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM - 1)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isInFrontOnLeftLane.holds(tick, vehicle2 to vehicle1))
  }

  // endregion

  // region behind

  /** Tests the [isBehindOnLeftLane] predicate for vehicles behind each other. */
  @Test
  fun `Test vehicle behind on left lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnLeftLane] predicate for vehicles behind each other at max distance. */
  @Test
  fun `Test vehicle behind on left lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnLeftLane] predicate for vehicles behind each other at max distance-1. */
  @Test
  fun `Test vehicle behind on left lane at max distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO - 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnLeftLane] predicate for vehicles behind each other at min distance. */
  @Test
  fun `Test vehicle behind on left lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnLeftLane] predicate for vehicles behind each other at min distance+1. */
  @Test
  fun `Test vehicle behind on left lane at min distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM + 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnLeftLane.holds(tick, behindVehicle to frontVehicle))
  }
  // endregion
}
