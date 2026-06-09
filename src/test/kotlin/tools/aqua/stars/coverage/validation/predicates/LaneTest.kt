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
import tools.aqua.stars.coverage.significance.tsc.isOnLeftLane
import tools.aqua.stars.coverage.significance.tsc.isOnMiddleLane
import tools.aqua.stars.coverage.significance.tsc.isOnRightLane
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.allLanePredicates
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

/** Test class for lane predicates: [isOnLeftLane], [isOnMiddleLane], and [isOnRightLane]. */
class LaneTest {

  /** Tests the [isOnLeftLane] predicate. */
  @Test
  fun `Test is on left lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane)

    val tick = getTestTimeStep(listOf(vehicle1), ego = vehicle1)
    assert(isOnLeftLane.holds(tick))
    allLanePredicates.minus(isOnLeftLane).forEach { predicate -> assert(!predicate.holds(tick)) }
  }

  /** Tests the [isOnMiddleLane] predicate. */
  @Test
  fun `Test is on middle lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane)

    val tick = getTestTimeStep(listOf(vehicle1), ego = vehicle1)
    assert(isOnMiddleLane.holds(tick))
    allLanePredicates.minus(isOnMiddleLane).forEach { predicate -> assert(!predicate.holds(tick)) }
  }

  /** Tests the [isOnRightLane] predicate. */
  @Test
  fun `Test is on right lane`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val vehicle1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane)

    val tick = getTestTimeStep(listOf(vehicle1), ego = vehicle1)
    assert(isOnRightLane.holds(tick))
    allLanePredicates.minus(isOnRightLane).forEach { predicate -> assert(!predicate.holds(tick)) }
  }
}
