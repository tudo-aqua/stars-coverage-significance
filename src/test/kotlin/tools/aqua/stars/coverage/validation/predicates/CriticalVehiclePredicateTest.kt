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
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.tsc.hasCriticalVehicleInBehind
import tools.aqua.stars.coverage.significance.tsc.hasCriticalVehicleInFront
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestVehicle

/**
 * Test class for [hasCriticalVehicleInFront] and [hasCriticalVehicleInBehind].
 *
 * Test vehicles are 2 m long (see [PredicateTestHelper.getTestVehicle]). In the closing cases below
 * the bumper-to-bumper gap is 18 m and the closing speed is 25 m/s, i.e. a time-to-collision of
 * 0.72 s, which is below the critical threshold of 2 s.
 */
class CriticalVehiclePredicateTest {
  private val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()

  /** A closing vehicle in front on the ego's lane is critical. */
  @Test
  fun `Test closing vehicle in front on same lane is critical`() {
    val ego = getTestVehicle("ego", roadNetwork.middleLane, 50.0f, 100.0f)
    val other = getTestVehicle("other", roadNetwork.middleLane, 70.0f, 10.0f)
    val tick = getTestTimeStep(listOf(ego, other), ego = ego)

    assertTrue(hasCriticalVehicleInFront.holds(tick))
    assertFalse(hasCriticalVehicleInBehind.holds(tick))
  }

  /** A non-closing vehicle in front on the ego's lane with a small time gap is critical. */
  @Test
  fun `Test vehicle in front on same lane with small time gap is critical`() {
    val ego = getTestVehicle("ego", roadNetwork.middleLane, 50.0f, 100.0f)
    val other = getTestVehicle("other", roadNetwork.middleLane, 60.0f, 100.0f)
    val tick = getTestTimeStep(listOf(ego, other), ego = ego)

    assertTrue(hasCriticalVehicleInFront.holds(tick))
  }

  /** A vehicle in front with a low time-to-collision on another lane is not critical. */
  @Test
  fun `Test closing vehicle in front on other lane is not critical`() {
    val ego = getTestVehicle("ego", roadNetwork.middleLane, 50.0f, 100.0f)
    listOf(roadNetwork.leftLane, roadNetwork.rightLane).forEach { lane ->
      val other = getTestVehicle("other", lane, 70.0f, 10.0f)
      val tick = getTestTimeStep(listOf(ego, other), ego = ego)

      assertFalse(hasCriticalVehicleInFront.holds(tick))
      assertFalse(hasCriticalVehicleInBehind.holds(tick))
    }
  }

  /** A closing vehicle behind on the ego's lane is critical. */
  @Test
  fun `Test closing vehicle behind on same lane is critical`() {
    val ego = getTestVehicle("ego", roadNetwork.middleLane, 50.0f, 10.0f)
    val other = getTestVehicle("other", roadNetwork.middleLane, 30.0f, 100.0f)
    val tick = getTestTimeStep(listOf(ego, other), ego = ego)

    assertTrue(hasCriticalVehicleInBehind.holds(tick))
    assertFalse(hasCriticalVehicleInFront.holds(tick))
  }

  /** A vehicle behind with a low time-to-collision on another lane is not critical. */
  @Test
  fun `Test closing vehicle behind on other lane is not critical`() {
    val ego = getTestVehicle("ego", roadNetwork.middleLane, 50.0f, 10.0f)
    listOf(roadNetwork.leftLane, roadNetwork.rightLane).forEach { lane ->
      val other = getTestVehicle("other", lane, 30.0f, 100.0f)
      val tick = getTestTimeStep(listOf(ego, other), ego = ego)

      assertFalse(hasCriticalVehicleInBehind.holds(tick))
      assertFalse(hasCriticalVehicleInFront.holds(tick))
    }
  }
}
