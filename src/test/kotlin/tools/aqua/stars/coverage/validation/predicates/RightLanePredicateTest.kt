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
import tools.aqua.stars.coverage.significance.isBehindOnRightLane
import tools.aqua.stars.coverage.significance.isInFrontOnRightLane
import tools.aqua.stars.coverage.significance.isOnRightLaneOf
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.allRelativePositionPredicates
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

class RightLanePredicateTest {

  /** Tests the [isOnRightLaneOf] predicate for right lanes. */
  @Test
  fun `Test vehicles on right lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(isOnRightLaneOf.holds(tick, vehicle1 to vehicle2))
    allRelativePositionPredicates.minus(isOnRightLaneOf).forEach { predicate ->
      assert(!predicate.holds(tick, vehicle1 to vehicle2))
    }
  }

  /** Tests the [isOnRightLaneOf] predicate for different lanes. */
  @Test
  fun `Test vehicles on different lanes`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isOnRightLaneOf.holds(tick, vehicle1 to vehicle2))
  }

  // region in front
  /** Tests the [isInFrontOnRightLane] predicate for vehicles in front of each other. */
  @Test
  fun `Test vehicle in front on right lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnRightLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnRightLane] predicate for vehicles in front of each other at max distance.
   */
  @Test
  fun `Test vehicle in front on right lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.rightLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnRightLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnRightLane] predicate for vehicles in front of each other at max
   * distance+1.
   */
  @Test
  fun `Test vehicle in front on right lane at max distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.rightLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO + 1)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isInFrontOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }

  /**
   * Tests the [isInFrontOnRightLane] predicate for vehicles in front of each other at min distance.
   */
  @Test
  fun `Test vehicle in front on right lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.rightLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnRightLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnRightLane] predicate for vehicles in front of each other at min
   * distance-1.
   */
  @Test
  fun `Test vehicle in front on right lane at min distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)
    val vehicle2 =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.rightLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM - 1)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isInFrontOnRightLane.holds(tick, vehicle2 to vehicle1))
  }

  // endregion

  // region behind

  /** Tests the [isBehindOnRightLane] predicate for vehicles behind each other. */
  @Test
  fun `Test vehicle behind on right lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.middleLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnRightLane] predicate for vehicles behind each other at max distance. */
  @Test
  fun `Test vehicle behind on right lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.rightLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnRightLane] predicate for vehicles behind each other at max distance-1. */
  @Test
  fun `Test vehicle behind on right lane at max distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.rightLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO - 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnRightLane] predicate for vehicles behind each other at min distance. */
  @Test
  fun `Test vehicle behind on right lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.rightLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnRightLane] predicate for vehicles behind each other at min distance+1. */
  @Test
  fun `Test vehicle behind on right lane at min distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.rightLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM + 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnRightLane.holds(tick, behindVehicle to frontVehicle))
  }
  // endregion
}
