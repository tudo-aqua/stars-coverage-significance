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

package tools.aqua.stars.coverage.validation.utils

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toSetOfMonitorViolations

/** Tests for [MonitorViolation]s bitmask conversion functions. */
class MonitorViolationsBitmaskTest {

  /** Test correct creation of bitmask from [Set] of [MonitorViolation]s. */
  @Test
  fun `Test correct creation of bitmask from set of monitor violations`() {
    val setOfViolations = setOf(MonitorViolation.G0Accidents, MonitorViolation.G1SafeDistance)
    assertEquals(
        2.0.pow(MonitorViolation.G0Accidents.bit).toInt() +
            2.0.pow(MonitorViolation.G1SafeDistance.bit).toInt(),
        setOfViolations.toBitmask())
  }

  /** Test correct creation of [Set] of [MonitorViolation]s from bitmask. */
  @Test
  fun `Test correct creation of set of monitor violations from bitmask`() {
    val bitmask =
        2.0.pow(MonitorViolation.G0Accidents.bit).toInt() +
            2.0.pow(MonitorViolation.G1SafeDistance.bit).toInt()
    assertEquals(
        setOf(MonitorViolation.G0Accidents, MonitorViolation.G1SafeDistance),
        bitmask.toSetOfMonitorViolations())
  }

  /** Test correct creation of [Set] of [MonitorViolation]s from bitmask with one violation. */
  @Test
  fun `Test correct creation of set of monitor violations from bitmask with one violation`() {
    val bitmask = 2.0.pow(MonitorViolation.G0Accidents.bit).toInt()
    assertEquals(setOf(MonitorViolation.G0Accidents), bitmask.toSetOfMonitorViolations())
  }

  /** Test correct creation of bitmask from [Set] of [MonitorViolation]s with one violation. */
  @Test
  fun `Test correct creation of bitmask from set of monitor violations with one violation`() {
    val setOfViolations = setOf(MonitorViolation.G0Accidents)
    assertEquals(2.0.pow(MonitorViolation.G0Accidents.bit).toInt(), setOfViolations.toBitmask())
  }

  /** Test correct creation of bitmask from empty [Set] of [MonitorViolation]s. */
  @Test
  fun `Test correct creation of bitmask from empty set of monitor violations`() {
    val setOfViolations = emptySet<MonitorViolation>()
    assertEquals(0, setOfViolations.toBitmask())
  }

  /** Test correct creation of [Set] of [MonitorViolation]s from bitmask with no violations. */
  @Test
  fun `Test correct creation of set of monitor violations from bitmask with no violations`() {
    val bitmask = 0
    assertEquals(emptySet(), bitmask.toSetOfMonitorViolations())
  }
}
