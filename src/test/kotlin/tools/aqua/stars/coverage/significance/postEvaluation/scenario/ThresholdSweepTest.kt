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

/**
 * Tests for [ThresholdSweep] on a hand-built [OutcomeMatrices], without the database. Two scenarios
 * and one mutant: the baseline violates TTC front in scenario 0 (after a lane change), the mutant
 * in scenario 1, where it also has an accident.
 */
class ThresholdSweepTest {

  private val inf = Float.POSITIVE_INFINITY

  private val data =
      OutcomeMatrices(
          scenarioCount = 2,
          mutantCount = 1,
          baselineAccident = booleanArrayOf(false, false),
          baselineLaneChanged = booleanArrayOf(true, false),
          baseline = OracleMeasure.entries.associateWith { floatArrayOf(0.5f, inf) },
          mutantAccident = booleanArrayOf(false, true),
          mutant = OracleMeasure.entries.associateWith { floatArrayOf(inf, 0.5f) },
      )

  /** Scenarios the baseline violates leave the pool; only the remaining ones can kill. */
  @Test
  fun `baseline violations leave the pool and only the rest counts as kills`() {
    val s = ThresholdSweep.evaluate(data, listOf(OracleMeasure.TTC_FRONT to 1.0f))
    assertEquals(1, s.baselineViolations)
    assertEquals(1, s.baselineViolationsWithLaneChange)
    assertEquals(1, s.pool)
    assertEquals(1, s.killableMutants)
    assertEquals(1L, s.killPairs)
    assertEquals(1.0, s.jaccardWithG0)
  }

  /** A violation needs `min < threshold`, so a value equal to the threshold does not count. */
  @Test
  fun `threshold at the value is not a violation`() {
    val s = ThresholdSweep.evaluate(data, listOf(OracleMeasure.TTC_FRONT to 0.5f))
    assertEquals(0, s.baselineViolations)
    assertEquals(0L, s.killPairs)
  }

  /** The choice is the largest grid value whose pool loss stays within the limit, or none. */
  @Test
  fun `picks the largest threshold within the pool-loss limit`() {
    // The baseline violates half the scenarios from 0.7 s on, so only 0.5 s stays within 5%.
    val (_, chosen) = ThresholdSweep.sweep(data, maxPoolLoss = 0.05)
    assertEquals(0.5f, chosen.perSide["tg/front/always"])
    assertEquals(null, chosen.perSide["ttc/front/always"])
  }
}
