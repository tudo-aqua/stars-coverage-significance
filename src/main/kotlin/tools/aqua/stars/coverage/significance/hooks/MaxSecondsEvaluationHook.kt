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
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * [PreTickEvaluationHook] that checks if the tick time of a [TimeStep] is less than or equal to
 * [maxSeconds] seconds. If the tick time exceeds [maxSeconds] seconds, the hook returns
 * [EvaluationHookResult.SKIP], otherwise it returns [EvaluationHookResult.OK].
 *
 * @property maxSeconds The maximum allowed tick time in seconds. Must be non-negative.
 */
class MaxSecondsEvaluationHook(val maxSeconds: Int = 10) :
    PreTickEvaluationHook<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>(
        identifier = "MaxSecondsEvaluationHook") {
  init {
    require(maxSeconds >= 0) { "maxSeconds must be >= 0" }
  }

  override fun evaluate(tick: TimeStep): EvaluationHookResult =
      if (tick.tickTimeMillis > maxSeconds * 1000L) {
        EvaluationHookResult.SKIP
      } else {
        EvaluationHookResult.OK
      }
}
