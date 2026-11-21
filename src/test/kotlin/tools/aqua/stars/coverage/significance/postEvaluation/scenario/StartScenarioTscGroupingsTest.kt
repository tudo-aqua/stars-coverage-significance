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

package tools.aqua.stars.coverage.significance.postEvaluation.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot
import tools.aqua.stars.coverage.significance.tsc.tscWithStaticDistances
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper
import tools.aqua.stars.coverage.validation.predicates.RoadNetworkTestHelpers

/** Tests for the tick-0 rebuild of [StartScenarioTscGroupings]. */
class StartScenarioTscGroupingsTest {

  private val network = RoadNetworkTestHelpers.threeLaneSingleEdgeNetwork(laneLengthMeters = 300f)
  private val lanes = network.lanes.associateBy { it.laneIndex }
  private val types = mapOf("ego" to PredicateTestHelper.getDefaultVehicleType())

  /** Ego in the middle lane at 110 m, a slower car ahead in its lane, one on the left lane. */
  private val snapshots =
      listOf(
          TickVehicleSnapshot(
              "veh0",
              ego = true,
              type = "ego",
              lane = 1,
              front = 110f,
              back = 105f,
              speed = 22f,
              accel = 0f),
          TickVehicleSnapshot(
              "veh1",
              type = "car_calm",
              lane = 1,
              front = 170f,
              back = 165f,
              speed = 14f,
              accel = 0f),
          TickVehicleSnapshot(
              "veh2",
              type = "car_normal",
              lane = 2,
              front = 110f,
              back = 105f,
              speed = 22f,
              accel = 0f),
      )

  /** Lanes, positions, speeds and the ego come from the snapshots. */
  @Test
  fun `rebuilds the scene from the snapshots`() {
    val step = StartScenarioTscGroupings.timeStep(snapshots, lanes, types, scenarioConfigId = 7)
    assertEquals("veh0", step.ego.vehicleId)
    assertEquals(listOf(1, 1, 2), step.vehiclesInTick.map { it.currentLane.laneIndex })
    assertEquals(listOf(110f, 170f, 110f), step.vehiclesInTick.map { it.positionOnLaneMeters })
    assertEquals(105f, step.ego.backBumperPositionOnLaneMeters)
    assertEquals(14f, step.vehiclesInTick[1].speedMetersPerSecond)
    assertEquals(7, step.scenarioConfigId)
    assertEquals(0L, step.tickTimeMillis)
  }

  /** The static TSC evaluated on a rebuilt scene matches one of its seeded instances by JSON. */
  @Test
  fun `static TSC instance is one of its possible instances`() {
    val tsc = tscWithStaticDistances()
    val step = StartScenarioTscGroupings.timeStep(snapshots, lanes, types, scenarioConfigId = 7)
    val json = tsc.evaluate(step).getJsonString()
    assertTrue(tsc.possibleTSCInstances.any { it.getJsonString() == json })
  }
}
