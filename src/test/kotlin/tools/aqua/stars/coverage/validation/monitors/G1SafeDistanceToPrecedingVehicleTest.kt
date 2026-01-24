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

package tools.aqua.stars.coverage.validation.monitors

import kotlin.test.Test
import kotlin.test.assertEquals
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.coverage.significance.isOnSameLane
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep
import tools.aqua.stars.coverage.validation.predicates.RoadNetworkTestHelpers
import tools.aqua.stars.coverage.validation.predicates.leftLane
import tools.aqua.stars.coverage.validation.predicates.middleLane
import tools.aqua.stars.logic.kcmftbl.past.previous

class G1SafeDistanceToPrecedingVehicleTest {

  @Test
  fun `Test correct cut-in detection`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego1 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.middleLane, 0.0f)
    val otherVehicle1 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, 0.0f)
    val tick1 = getTestTimeStep(listOf(ego1, otherVehicle1), ego = ego1)

    val ego2 = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)
    val otherVehicle2 = PredicateTestHelper.getTestVehicle("v2", roadNetwork.leftLane, 0.0f)
    val tick2 = getTestTimeStep(listOf(ego2, otherVehicle2), ego = ego2)

    val ticks = listOf(tick1, tick2).asTickSequence().toList()
    val firstTick = ticks[1]
    val secondTick = ticks[1].nextTick
    checkNotNull(secondTick)
    assertEquals(secondTick, tick2)
    assert(
        !isOnSameLane.holds(
            firstTick, firstTick.ego to firstTick.getVehicleById(otherVehicle1.vehicleId)))
    assert(
        isOnSameLane.holds(
            firstTick, secondTick.ego to secondTick.getVehicleById(otherVehicle2.vehicleId)))
    assert(
        previous(secondTick) { tick ->
          !isOnSameLane.holds(
              tick, firstTick.ego to firstTick.getVehicleById(otherVehicle1.vehicleId))
        })
  }
}
