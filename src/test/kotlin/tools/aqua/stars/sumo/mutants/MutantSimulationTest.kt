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

package tools.aqua.stars.sumo.mutants

import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.BOTTOM_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector
import tools.aqua.stars.sumo.Mutant
import tools.aqua.stars.sumo.MutantManeuver

/**
 * Drives the original Autopilot (index `-1`) and every mutant in [AutopilotMutants.byIndex] in a
 * live SUMO simulation and checks that each one actually performs an action on the ego vehicle:
 * - [Mutant.controlTick] runs for [CONTROL_TICKS] steps without throwing and reports a finite
 *   speed,
 * - after every step, the ego drives at exactly the speed the mutant reported in the previous
 *   tick's [MutantManeuver] (i.e. the command reached SUMO and the reported maneuver, which is what
 *   gets recorded per tick, is what was really executed), and
 * - the ego's speed changes at least once. With speed mode 0 an ego nobody controls just coasts at
 *   its placement speed, so this rules out a mutant that never commands anything.
 *
 * Vehicles are placed via [LibsumoDynamicDataCollector.runGeneratedScenario] with `onlyFirstTick`,
 * and the control loop below then mirrors that function's own loop (which can't be used directly,
 * as it looks the mutant up in the database). Keep the two in sync.
 *
 * Requires the native libsumo/SUMO runtime, like `LibsumoDynamicDataCollectorPlacementTest`.
 */
class MutantSimulationTest {

  /** One simulation per mutant, controlling the ego for [CONTROL_TICKS] steps. */
  @TestFactory
  fun `every mutant performs an action in the simulation`(): List<DynamicTest> =
      (listOf(-1) + AutopilotMutants.byIndex.keys.sorted()).map { index ->
        val name = AutopilotMutants.byIndex[index]?.simpleName ?: "Autopilot"
        DynamicTest.dynamicTest("$name (index $index)") {
          assertPerformsAction(AutopilotMutants.create(index))
        }
      }

  /** Places [scenario], hands the ego to [mutant] and checks the conditions in the class KDoc. */
  private fun assertPerformsAction(mutant: Mutant) {
    try {
      LibsumoDynamicDataCollector(
              baseDir = Path("src/test/resources"), netFileName = "grid_highway.net.xml")
          .runGeneratedScenario(runId = 1, scenario = scenario, mutantId = 1, onlyFirstTick = true)

      SumoVehicle.setSpeedMode(EGO_ID, 0)
      SumoVehicle.setLaneChangeMode(EGO_ID, 0)
      val placementSpeed = SumoVehicle.getSpeed(EGO_ID)

      var previousManeuver: MutantManeuver? = null
      var speedChanged = false
      repeat(CONTROL_TICKS) { tick ->
        Simulation.step()
        if (EGO_ID !in SumoVehicle.getIDList()) fail("ego left the simulation at tick $tick")

        val actualSpeed = SumoVehicle.getSpeed(EGO_ID)
        previousManeuver?.let {
          assertTrue(
              abs(actualSpeed - it.newSpeedMps) < SPEED_TOLERANCE_MPS,
              "tick $tick: mutant reported ${it.newSpeedMps} m/s but the ego drives at " +
                  "$actualSpeed m/s")
        }
        if (abs(actualSpeed - placementSpeed) > SPEED_TOLERANCE_MPS) speedChanged = true

        val maneuver = mutant.controlTick(EGO_ID)
        assertTrue(
            maneuver.newSpeedMps.isFinite(), "tick $tick: non-finite speed ${maneuver.newSpeedMps}")
        previousManeuver = maneuver
      }

      assertTrue(
          speedChanged,
          "ego kept its placement speed of $placementSpeed m/s for all $CONTROL_TICKS ticks")
    } finally {
      Simulation.close()
    }
  }

  private companion object {
    /** Vehicle id [LibsumoDynamicDataCollector] gives the ego. */
    const val EGO_ID = "ego"

    /** Number of control ticks per mutant (5 simulated seconds at SUMO's 0.1 s step). */
    const val CONTROL_TICKS = 50

    /** Allowed difference between a reported and an executed speed. */
    const val SPEED_TOLERANCE_MPS = 0.01

    /**
     * The ego in the center lane with a slower leader ahead, a faster vehicle ahead on its left and
     * one behind on its right, so leader following and both lane-change directions are relevant.
     */
    val scenario =
        Array(3) { arrayOfNulls<Spawn>(3) }
            .apply {
              this[MIDDLE_ROW][CENTER_LANE] =
                  Spawn(MIDDLE_ROW, CENTER_LANE, 1000.0f, GridVehicleType.EGO)
              this[TOP_ROW][CENTER_LANE] =
                  Spawn(TOP_ROW, CENTER_LANE, 1060.0f, GridVehicleType.CALM)
              this[TOP_ROW][LEFT_LANE] = Spawn(TOP_ROW, LEFT_LANE, 1100.0f, GridVehicleType.SPEEDY)
              this[BOTTOM_ROW][RIGHT_LANE] =
                  Spawn(BOTTOM_ROW, RIGHT_LANE, 900.0f, GridVehicleType.NORMAL)
            }
            .let { GeneratedScenario(it).toScenarioStartingConfigurationEntry(id = 1) }
  }
}
