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

package tools.aqua.stars.coverage.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.BOTTOM_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW

/** Test class for [Spawn] related functionality. */
class TestSpawns {

  /** Test correct conversion from Spawn to ScenarioStartingConfigurationEntry and back. */
  @Test
  fun `Test correct conversion from Spawn to ScenarioStartingConfigurationEntry and back`() {
    val spawns = Array(3) { arrayOfNulls<Spawn>(3) }
    spawns[TOP_ROW][LEFT_LANE] = Spawn(TOP_ROW, LEFT_LANE, 170.3f, GridVehicleType.CALM, 70)
    spawns[TOP_ROW][CENTER_LANE] = Spawn(TOP_ROW, CENTER_LANE, 200.0f, GridVehicleType.NORMAL, 90)
    spawns[TOP_ROW][RIGHT_LANE] = Spawn(TOP_ROW, RIGHT_LANE, 121.7f, GridVehicleType.SPEEDY, 130)

    spawns[MIDDLE_ROW][LEFT_LANE] =
        Spawn(MIDDLE_ROW, LEFT_LANE, 101.2f, GridVehicleType.SPORTY, 110)
    spawns[MIDDLE_ROW][CENTER_LANE] =
        Spawn(MIDDLE_ROW, CENTER_LANE, 110.0f, GridVehicleType.EGO, 100)
    spawns[MIDDLE_ROW][RIGHT_LANE] =
        Spawn(MIDDLE_ROW, RIGHT_LANE, 119.9f, GridVehicleType.NORMAL, 90)

    spawns[BOTTOM_ROW][LEFT_LANE] = Spawn(BOTTOM_ROW, LEFT_LANE, 0.0f, GridVehicleType.NORMAL, 90)
    spawns[BOTTOM_ROW][CENTER_LANE] =
        Spawn(BOTTOM_ROW, CENTER_LANE, 40.4f, GridVehicleType.SPEEDY, 130)
    spawns[BOTTOM_ROW][RIGHT_LANE] = Spawn(BOTTOM_ROW, RIGHT_LANE, 99.5f, GridVehicleType.CALM, 70)

    val generatedScenario = GeneratedScenario(spawns, enumerationIndex = 42L, positionDrawIndex = 1)
    val startingScenario = generatedScenario.toScenarioStartingConfigurationEntry()
    val generatedScenarioFromStartingScenario = startingScenario.toGeneratedScenario()
    assertEquals(generatedScenario, generatedScenarioFromStartingScenario)
    assertEquals(generatedScenario.id, startingScenario.humanReadableScenarioId)
  }

  /** The human-readable id names the cell, the type, the position at 0.1 m and the speed. */
  @Test
  fun `the human-readable id encodes cell, type, position and speed`() {
    val spawns =
        listOf(
            Spawn(MIDDLE_ROW, CENTER_LANE, 110.0f, GridVehicleType.EGO, 80),
            Spawn(TOP_ROW, CENTER_LANE, 163.4f, GridVehicleType.SPORTY, 110),
            Spawn(BOTTOM_ROW, LEFT_LANE, 12.2f, GridVehicleType.SPEEDY, 130),
        )
    assertEquals(
        "[0][2]S@12.2v130__[1][1]E@110.0v80__[2][1]P@163.4v110", GeneratedScenario(spawns).id)
  }
}
