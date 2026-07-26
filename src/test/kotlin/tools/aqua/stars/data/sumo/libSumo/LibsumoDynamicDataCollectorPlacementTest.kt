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

package tools.aqua.stars.data.sumo.libSumo

import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.BOTTOM_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW

/**
 * Verifies the vehicle-placement invariant [LibsumoDynamicDataCollector] relies on: a vehicle's
 * exact configured departure speed must be observable at the very first tick after placement.
 * `vehicle.moveTo` (force-placement onto the exact lane/position in
 * [LibsumoDynamicDataCollector.forcePlaceVehicles]) silently resets whatever speed `vehicle.add`'s
 * `departSpeed` established at insertion, so the fix re-applies `vehicle.setSpeed` right
 * afterwards. Beyond that first tick, SUMO's own car-following model must be free to change vehicle
 * speeds — that must *not* be forced again, so the second test below asserts the opposite: that
 * speeds are allowed to diverge from the placement speed from tick 2 onward.
 *
 * Requires the native libsumo/SUMO runtime (as installed in the project's Docker image); not
 * runnable on a machine without SUMO's shared libraries on `PATH`/`LD_LIBRARY_PATH`.
 */
class LibsumoDynamicDataCollectorPlacementTest {

  private fun collector() =
      LibsumoDynamicDataCollector(
          baseDir = Path("src/test/resources"), netFileName = "grid_highway.net.xml")

  /**
   * Every placement speed is requested 10 km/h below the type's configured
   * [GridVehicleType.departSpeedKmh] (see [LibsumoDynamicDataCollector.runGeneratedScenario]).
   * Recomputed here from the same public constant instead of hard-coding the resulting m/s value,
   * so a change to that offset shows up as an assertion diff instead of silently passing.
   */
  private fun expectedPlacementSpeedMps(type: GridVehicleType): Double =
      (type.departSpeedKmh - 10) / 3.6

  /** Asserts [actualMps] matches [type]'s [expectedPlacementSpeedMps] within float rounding. */
  private fun assertPlacementSpeed(type: GridVehicleType, vehId: String, actualMps: Float) {
    val expected = expectedPlacementSpeedMps(type)
    assertTrue(
        abs(expected - actualMps) < 1e-3,
        "expected $vehId to be placed at ${expected}m/s (${type.name}'s departSpeedKmh=" +
            "${type.departSpeedKmh} minus 10km/h) but was ${actualMps}m/s")
  }

  @Test
  fun `every vehicle has its exact configured departure speed at the first tick`() {
    val spawns = Array(3) { arrayOfNulls<Spawn>(3) }
    spawns[MIDDLE_ROW][CENTER_LANE] = Spawn(MIDDLE_ROW, CENTER_LANE, 1000.0f, GridVehicleType.EGO)
    spawns[BOTTOM_ROW][RIGHT_LANE] = Spawn(BOTTOM_ROW, RIGHT_LANE, 700.0f, GridVehicleType.CALM)
    spawns[BOTTOM_ROW][CENTER_LANE] = Spawn(BOTTOM_ROW, CENTER_LANE, 700.0f, GridVehicleType.NORMAL)
    spawns[TOP_ROW][RIGHT_LANE] = Spawn(TOP_ROW, RIGHT_LANE, 1300.0f, GridVehicleType.SPEEDY)

    val scenario = GeneratedScenario(spawns).toScenarioStartingConfigurationEntry(id = 1)

    try {
      val ticks =
          collector()
              .runGeneratedScenario(
                  runId = 1, scenario = scenario, mutantId = 1, onlyFirstTick = true)
      val firstTick = ticks.single()
      val byId = firstTick.vehiclesInTick.associateBy { it.vehicleId }

      assertPlacementSpeed(GridVehicleType.EGO, "ego", byId.getValue("ego").speedMetersPerSecond)
      assertPlacementSpeed(
          GridVehicleType.CALM, "slow_1", byId.getValue("slow_1").speedMetersPerSecond)
      assertPlacementSpeed(
          GridVehicleType.NORMAL, "normal_1", byId.getValue("normal_1").speedMetersPerSecond)
      assertPlacementSpeed(
          GridVehicleType.SPEEDY, "fast_1", byId.getValue("fast_1").speedMetersPerSecond)
    } finally {
      Simulation.close()
    }
  }

  @Test
  fun `after the first tick, SUMO's own dynamics govern speed instead of the placement speed`() {
    // A speedy car placed 10m behind a much slower leader in the same lane: far short of the
    // ~35m safe following distance its departure speed would need (v*tau + minGap), so SUMO's
    // car-following model must brake it -- proving nothing in our code re-pins it to the
    // placement speed after tick 1. A single 0.1s step only moves the needle by the vehicle's
    // per-step decel bound (~4.5 m/s^2 * 0.1s = ~0.45 m/s), so several steps are taken to build
    // up a clearly-outside-noise cumulative difference instead of asserting on one step's tiny
    // change.
    val spawns = Array(3) { arrayOfNulls<Spawn>(3) }
    spawns[MIDDLE_ROW][CENTER_LANE] = Spawn(MIDDLE_ROW, CENTER_LANE, 1000.0f, GridVehicleType.EGO)
    spawns[BOTTOM_ROW][RIGHT_LANE] = Spawn(BOTTOM_ROW, RIGHT_LANE, 1000.0f, GridVehicleType.CALM)
    spawns[TOP_ROW][RIGHT_LANE] = Spawn(TOP_ROW, RIGHT_LANE, 990.0f, GridVehicleType.SPEEDY)

    val scenario = GeneratedScenario(spawns).toScenarioStartingConfigurationEntry(id = 2)

    try {
      val ticks =
          collector()
              .runGeneratedScenario(
                  runId = 1, scenario = scenario, mutantId = 1, onlyFirstTick = true)
      val placementSpeed =
          ticks.single().vehiclesInTick.first { it.vehicleId == "fast_1" }.speedMetersPerSecond

      assertPlacementSpeed(GridVehicleType.SPEEDY, "fast_1", placementSpeed)

      // The collector leaves the simulation loaded after onlyFirstTick=true; step it several more
      // times directly to observe what SUMO itself does afterward, without going through the full
      // autopilot-driven loop (which needs a live DB for the mutant lookup). One step's ~0.45 m/s
      // max change would be indistinguishable from noise, so ten steps (1 simulated second) are
      // taken to build up a clearly-outside-noise cumulative difference.
      repeat(10) { Simulation.step() }
      val speedAfterTenMoreSteps = SumoVehicle.getSpeed("fast_1")

      assertTrue(
          abs(speedAfterTenMoreSteps - placementSpeed) > 1.0,
          "expected SUMO's car-following model to have visibly changed fast_1's speed after " +
              "placement (was $placementSpeed, still $speedAfterTenMoreSteps)")
    } finally {
      Simulation.close()
    }
  }
}
