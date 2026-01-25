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
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.coverage.significance.g0Accidents
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep
import tools.aqua.stars.coverage.validation.predicates.RoadNetworkTestHelpers
import tools.aqua.stars.coverage.validation.predicates.leftLane
import tools.aqua.stars.coverage.validation.predicates.rightLane
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep

/** Test class for G_0: Accidents monitor. */
class G0AccidentsTest {

  /** Tests the [g0Accidents] monitor with no accidents. */
  @Test
  fun `Test G_0 accidents monitor with no accidents`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane, 0.0f)
    val tick = getTestTimeStep(listOf(ego), ego = ego)
    assert(g0Accidents.holds(tick))
  }

  /** Tests the [g0Accidents] monitor with one accident of the ego and another vehicle. */
  @Test
  fun `Test G_0 accidents monitor with one accident of the ego and another vehicle`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane, 0.0f)
    val otherVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane, 0.0f)

    val tick =
        getTestTimeStep(
            listOf(ego, otherVehicle),
            collisions = listOf(100L to (ego to otherVehicle)),
        )
    assert(!g0Accidents.holds(tick))
  }

  /** Tests the [g0Accidents] monitor with two accidents of the ego and another vehicle. */
  @Test
  fun `Test G_0 accidents monitor with two accidents of the ego and another vehicle`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.rightLane, 0.0f)
    val otherVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane, 0.0f)
    val anotherVehicle = PredicateTestHelper.getTestVehicle("v3", roadNetwork.rightLane, 0.0f)

    val tick =
        getTestTimeStep(
            listOf(ego, otherVehicle, anotherVehicle),
            collisions = listOf(100L to (ego to otherVehicle), 100L to (ego to anotherVehicle)))
    assert(!g0Accidents.holds(tick))
  }

  /** Tests the [g0Accidents] monitor with one accident in which the ego is not involved. */
  @Test
  fun `Test G_0 accidents monitor with one accident in which the ego is not involved`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)
    val otherVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane, 0.0f)
    val anotherVehicle = PredicateTestHelper.getTestVehicle("v3", roadNetwork.rightLane, 0.0f)

    val tick =
        getTestTimeStep(
            listOf(ego, otherVehicle, anotherVehicle),
            collisions = listOf(100L to (otherVehicle to anotherVehicle)))
    assert(g0Accidents.holds(tick))
  }

  /** Tests the [g0Accidents] monitor with an accident in the last tick of a tick sequence. */
  @Test
  fun `Test G_0 accidents monitor with accident in last tick`() {
    val roadNetwork = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork()
    val ego = PredicateTestHelper.getTestVehicle("v1", roadNetwork.leftLane, 0.0f)
    val otherVehicle = PredicateTestHelper.getTestVehicle("v2", roadNetwork.rightLane, 0.0f)
    val anotherVehicle = PredicateTestHelper.getTestVehicle("v3", roadNetwork.rightLane, 0.0f)

    val ticks = mutableListOf<TimeStep>()
    for (i in 0..100) {
      val tick = getTestTimeStep(listOf(ego, otherVehicle, anotherVehicle), i.toLong())
      ticks.add(tick)
    }
    ticks.add(
        getTestTimeStep(
            listOf(ego, otherVehicle, anotherVehicle),
            101L,
            collisions = listOf(100L to (ego to anotherVehicle))))
    val tickSequence = ticks.asTickSequence()
    assert(tickSequence.any { tick -> !g0Accidents.holds(tick) })
  }
}
