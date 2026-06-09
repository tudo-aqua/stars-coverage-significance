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
import kotlin.test.assertFalse
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.tsc.isBehindOnSameLane
import tools.aqua.stars.coverage.significance.tsc.isInFrontOnSameLane
import tools.aqua.stars.coverage.significance.tsc.isOnSameLane
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.allRelativePositionPredicates
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

/** Test class for same lane predicates. */
class SameLanePredicateTest {

  /** Tests the [isOnSameLane] predicate for same lanes. */
  @Test
  fun `Test vehicles on same lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(isOnSameLane.holds(tick, vehicle1 to vehicle2))
    allRelativePositionPredicates.minus(isOnSameLane).forEach { predicate ->
      assert(!predicate.holds(tick, vehicle1 to vehicle2))
    }
  }

  /** Tests the [isOnSameLane] predicate for different lanes. */
  @Test
  fun `Test vehicles on different lanes`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane)
    val vehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isOnSameLane.holds(tick, vehicle1 to vehicle2))
  }

  // region in front
  /** Tests the [isInFrontOnSameLane] predicate for vehicles in front of each other. */
  @Test
  fun `Test vehicle in front on same lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnSameLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnSameLane] predicate for vehicles in front of each other at max distance.
   */
  @Test
  fun `Test vehicle in front on same lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assertFalse(isInFrontOnSameLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnSameLane] predicate for vehicles in front of each other at max
   * distance+1.
   */
  @Test
  fun `Test vehicle in front on same lane at max distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO + 1)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isInFrontOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }

  /**
   * Tests the [isInFrontOnSameLane] predicate for vehicles in front of each other at min distance.
   */
  @Test
  fun `Test vehicle in front on same lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isInFrontOnSameLane.holds(tick, frontVehicle to behindVehicle))
  }

  /**
   * Tests the [isInFrontOnSameLane] predicate for vehicles in front of each other at min
   * distance-1.
   */
  @Test
  fun `Test vehicle in front on same lane at min distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)
    val vehicle2 =
        PredicateTestHelper.getTestVehicle(
            "v2", roadNetwork.leftLane, VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM - 1)

    val tick = getTestTimeStep(listOf(vehicle1, vehicle2))
    assert(!isInFrontOnSameLane.holds(tick, vehicle2 to vehicle1))
  }

  // endregion

  // region behind

  /** Tests the [isBehindOnSameLane] predicate for vehicles behind each other. */
  @Test
  fun `Test vehicle behind on same lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 50.0f)
    val behindVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, 20.0f)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnSameLane] predicate for vehicles behind each other at max distance. */
  @Test
  fun `Test vehicle behind on same lane at max distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(isBehindOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnSameLane] predicate for vehicles behind each other at max distance-1. */
  @Test
  fun `Test vehicle behind on same lane at max distance-1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO - 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnSameLane] predicate for vehicles behind each other at min distance. */
  @Test
  fun `Test vehicle behind on same lane at min distance`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assertFalse(isBehindOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }

  /** Tests the [isBehindOnSameLane] predicate for vehicles behind each other at min distance+1. */
  @Test
  fun `Test vehicle behind on same lane at min distance+1`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val frontVehicle = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 200.0f)
    val behindVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            frontVehicle.positionOnLaneMeters - VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM + 1)

    val tick = getTestTimeStep(listOf(frontVehicle, behindVehicle))
    assert(!isBehindOnSameLane.holds(tick, behindVehicle to frontVehicle))
  }
  // endregion
}
