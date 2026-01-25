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
import tools.aqua.stars.coverage.significance.SPEED_THRESHOLD_KMH
import tools.aqua.stars.coverage.significance.vehicleOnSameLaneInFrontIsFaster
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.allSpeedPredicatesSameLaneInFront
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

/** Test class for relative speed predicates. */
class RelativeSpeedTest {

  /** Test vehicle in front is faster with one vehicle in front. */
  @Test
  fun `Test vehicle in front same speed with one vehicle in front`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego =
        PredicateTestHelper.getTestVehicle(
            "v1", roadNetwork.leftLane, positionInMeters = 0.0f, speedKmH = 0.0f)
    val vehicle2 =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            positionInMeters = 50.0f,
            speedKmH = SPEED_THRESHOLD_KMH.toFloat())

    val tick = getTestTimeStep(listOf(ego, vehicle2), ego = ego)
    assert(vehicleOnSameLaneInFrontIsFaster.holds(tick))
    allSpeedPredicatesSameLaneInFront.minus(vehicleOnSameLaneInFrontIsFaster).forEach { predicate ->
      assert(!predicate.holds(tick))
    }
  }

  /** Test vehicle in front is faster with two vehicles in front. */
  @Test
  fun `Test vehicle in front same speed with two vehicles in front`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego =
        PredicateTestHelper.getTestVehicle(
            "v1", roadNetwork.leftLane, positionInMeters = 0.0f, speedKmH = 0.0f)
    val fasterVehicle =
        PredicateTestHelper.getTestVehicle(
            "v2",
            roadNetwork.leftLane,
            positionInMeters = 20.0f,
            speedKmH = SPEED_THRESHOLD_KMH.toFloat())
    val sameSpeedVehicle =
        PredicateTestHelper.getTestVehicle(
            "v3",
            roadNetwork.leftLane,
            positionInMeters = 50.0f,
            speedKmH = SPEED_THRESHOLD_KMH.toFloat() - 1)

    val tick = getTestTimeStep(listOf(ego, fasterVehicle, sameSpeedVehicle), ego = ego)
    val currentPredicate = vehicleOnSameLaneInFrontIsFaster
    assert(currentPredicate.holds(tick))
    allSpeedPredicatesSameLaneInFront.minus(currentPredicate).forEach { predicate ->
      assert(!predicate.holds(tick)) {
        "Predicate '${predicate.name}' should not hold alongside '${currentPredicate.name}'"
      }
    }
  }
}
