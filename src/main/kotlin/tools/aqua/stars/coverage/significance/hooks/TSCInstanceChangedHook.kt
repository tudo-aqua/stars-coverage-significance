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

package tools.aqua.stars.coverage.significance.hooks

import tools.aqua.stars.core.hooks.EvaluationHookResult
import tools.aqua.stars.core.hooks.PreTickEvaluationHook
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * [PreTickEvaluationHook] implements [PreTickEvaluationHook] and checks if the [TSCInstance] of a
 * [TSC] changes during the execution of a test case. If this is the case, the evaluation is
 * canceled at that point.
 *
 * @property tsc The [TSC] to check for instance changes.
 */
class TSCInstanceChangedHook(
    val tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>
) :
    PreTickEvaluationHook<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>(
        identifier = "TSCInstanceChangedHook") {

  /**
   * The first [TSCInstance] of the [TSC] that is evaluated. This is used to compare with subsequent
   * [TSCInstance]s to check if the instance changes during the execution of a test case.
   */
  private var firstInstance:
      TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>? =
      null

  /**
   * Evaluates the [TSCInstance] of the [TSC] at the current tick. If this is the first tick, the
   * [TSCInstance] is stored as the [firstInstance]. For subsequent ticks, the [TSCInstance] is
   * compared with the [firstInstance]. If they are different, the evaluation returns
   * [EvaluationHookResult.CANCEL], otherwise it returns [EvaluationHookResult.OK].
   */
  override fun evaluate(tick: TimeStep): EvaluationHookResult {
    val instance = tsc.evaluate(tick)
    if (tick.tickTimeMillis == 0L) {
      firstInstance = instance
      return EvaluationHookResult.OK
    }

    return if (instance != firstInstance) {
      EvaluationHookResult.CANCEL
    } else {
      EvaluationHookResult.OK
    }
  }
}
