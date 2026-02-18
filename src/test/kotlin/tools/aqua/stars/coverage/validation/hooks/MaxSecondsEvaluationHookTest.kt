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
import org.junit.jupiter.api.assertThrows
import tools.aqua.stars.core.hooks.EvaluationHookResult
import tools.aqua.stars.coverage.significance.hooks.MaxSecondsEvaluationHook
import tools.aqua.stars.coverage.validation.predicates.PredicateTestHelper.getTestTimeStep

class MaxSecondsEvaluationHookTest {

  /**
   * Tests the [MaxSecondsEvaluationHook] with a list of ticks with tick times from 0 to 10 seconds.
   * The hook should return [EvaluationHookResult.OK] for ticks with tick times less than or equal
   * to 5 seconds, and [EvaluationHookResult.SKIP] for ticks with tick times greater than 5 seconds.
   */
  @Test
  fun `Test MaxSecondsEvaluationHook with larger list of ticks`() {
    val hook = MaxSecondsEvaluationHook(maxSeconds = 5)

    for (i in 0..10) {
      val tickTime = i * 1000L // Convert seconds to milliseconds
      val tick = getTestTimeStep(tickTimeMillis = tickTime)
      val result = hook.evaluate(tick)

      if (tickTime > 5000L) {
        assertEquals(EvaluationHookResult.SKIP, result)
      } else {
        assertEquals(EvaluationHookResult.OK, result)
      }
    }
  }

  /**
   * Tests the [MaxSecondsEvaluationHook] with a tick that has a tick time of 3 seconds. The hook
   * should return [EvaluationHookResult.OK] since 3 seconds is less than the default maxSeconds of
   * 5 seconds.
   */
  @Test
  fun `Test MaxSecondsEvaluationHook with one tick in interval`() {
    val hook = MaxSecondsEvaluationHook(maxSeconds = 5)
    val tick = getTestTimeStep(tickTimeMillis = 3000L)
    val result = hook.evaluate(tick)
    assertEquals(EvaluationHookResult.OK, result)
  }

  /**
   * Tests the [MaxSecondsEvaluationHook] with a tick that has a tick time of 6 seconds. The hook
   * should return [EvaluationHookResult.SKIP] since 6 seconds is greater than the default
   * maxSeconds of 5 seconds.
   */
  @Test
  fun `Test MaxSecondsEvaluationHook with one tick outside interval`() {
    val hook = MaxSecondsEvaluationHook(maxSeconds = 5)
    val tick = getTestTimeStep(tickTimeMillis = 6000L)
    val result = hook.evaluate(tick)
    assertEquals(EvaluationHookResult.SKIP, result)
  }

  /**
   * Tests the [MaxSecondsEvaluationHook] with negative maxSeconds. The constructor should throw an
   * [IllegalArgumentException] when maxSeconds is negative.
   */
  @Test
  fun `Test MaxSecondsEvaluationHook with negative maxSeconds should throw exception`() {
    assertThrows<IllegalArgumentException> { MaxSecondsEvaluationHook(maxSeconds = -1) }
  }
}
