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
import tools.aqua.stars.coverage.significance.noVehicleBetweenOnSameLane
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

class NoVehicleBetweenPredicateTest {

  @Test
  fun `Test no vehicle is between ego and other vehicle`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego =
        PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, positionInMeters = 0.0f)
    val otherVehicle =
        PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, positionInMeters = 50.0f)

    val tick = getTestTimeStep(listOf(ego, otherVehicle))
    assert(noVehicleBetweenOnSameLane.holds(tick, ego to otherVehicle))
  }

  @Test
  fun `Test vehicle is between ego and other vehicle`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)
    val other = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, 50.0f)
    val between = PredicateTestHelper.getTestVehicle("v3", roadNetwork.leftLane, 20.0f)

    val tick = getTestTimeStep(listOf(ego, other, between), ego = ego)
    assert(!noVehicleBetweenOnSameLane.holds(tick, ego to other))
  }
}
