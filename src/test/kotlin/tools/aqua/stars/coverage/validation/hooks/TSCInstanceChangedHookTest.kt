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

package tools.aqua.stars.coverage.validation.hooks

import kotlin.test.Test
import kotlin.test.assertEquals
import tools.aqua.stars.core.hooks.EvaluationHookResult
import tools.aqua.stars.core.tsc.builder.tsc
import tools.aqua.stars.coverage.significance.hooks.TSCInstanceChangedHook
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestVehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/** Tests for [TSCInstanceChangedHook]. */
class TSCInstanceChangedHookTest {

  /** Helper function to create a simple TSC for testing purposes. */
  private fun testTSC() =
      tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("Static TSC") {
        optional("Root") {
          leaf("Has Ego Vehicle") {
            condition("") { tick -> tick.vehiclesInTick.any { it.vehicleId == "Ego" } }
          }
          leaf("Has Other Vehicles") {
            condition("") { tick -> tick.vehiclesInTick.any { it.vehicleId != "Ego" } }
          }
        }
      }

  /** Tests that the hook returns OK when the TSC instance does not change across ticks. */
  @Test
  fun `Test TSCInstanceChangedHookTest with same TSC Instance`() {
    val tsc = testTSC()
    val hook = TSCInstanceChangedHook(tsc)

    val tick1 = getTestTimeStep(tickTimeMillis = 0L, vehicles = listOf(getTestVehicle("Ego")))
    val tick2 = getTestTimeStep(tickTimeMillis = 1L, vehicles = listOf(getTestVehicle("Ego")))

    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick1))
    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick2))
  }

  /**
   * Tests that the hook returns CANCEL when the TSC instance changes across ticks. This is
   * simulated by changing the vehicles in the tick, which affects the TSC evaluation.
   */
  @Test
  fun `Test TSCInstanceChangedHookTest with changing TSC Instance`() {
    val tsc = testTSC()
    val hook = TSCInstanceChangedHook(tsc)

    val tick1 = getTestTimeStep(tickTimeMillis = 0L, vehicles = listOf(getTestVehicle("Ego")))
    val tick2 = getTestTimeStep(tickTimeMillis = 1L, vehicles = listOf(getTestVehicle("Ego")))
    val tick3 = getTestTimeStep(tickTimeMillis = 2L, vehicles = listOf(getTestVehicle("Other")))

    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick1))
    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick2))
    assertEquals(EvaluationHookResult.CANCEL, hook.evaluate(tick3))
  }

  /** Tests that the hook correctly reset after a previous evaluation of a valid tick sequence. */
  @Test
  fun `Test TSCInstanceChangedHookTest with resetting ticks`() {
    val tsc = testTSC()
    val hook = TSCInstanceChangedHook(tsc)

    val tick1 = getTestTimeStep(tickTimeMillis = 0L, vehicles = listOf(getTestVehicle("Ego")))
    val tick2 = getTestTimeStep(tickTimeMillis = 1L, vehicles = listOf(getTestVehicle("Other")))
    val tick3 = getTestTimeStep(tickTimeMillis = 0L, vehicles = listOf(getTestVehicle("Other")))

    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick1))
    assertEquals(EvaluationHookResult.CANCEL, hook.evaluate(tick2))
    assertEquals(EvaluationHookResult.OK, hook.evaluate(tick3))
  }
}
