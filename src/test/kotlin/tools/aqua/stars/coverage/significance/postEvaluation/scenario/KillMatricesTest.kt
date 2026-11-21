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

import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Tests for [KillMatrices.compute] on a hand-built [OutcomeMatrices], without the database.
 *
 * Scenarios 100, 101, 102. The baseline's front TTC is 1.0 s in scenario 100 (violation) and
 * infinite elsewhere. Controllers (`mutants.id`, row): 1 and 2 have front TTC 1.0 s in scenario 101
 * (identical kill columns), 3 never violates, 4 is AiPilot and violates in 102. In scenario 100 all
 * mutants violate except 3.
 */
class KillMatricesTest {

  private val inf = Float.POSITIVE_INFINITY

  private val ttcFront =
      floatArrayOf(
          1.0f,
          1.0f,
          inf, // id 1
          1.0f,
          1.0f,
          inf, // id 2
          inf,
          inf,
          inf, // id 3
          1.0f,
          inf,
          1.0f, // id 4, AiPilot
      )

  private val data =
      OutcomeMatrices(
          scenarioCount = 3,
          mutantCount = 4,
          baselineAccident = BooleanArray(3),
          baselineLaneChanged = BooleanArray(3),
          baseline =
              OracleMeasure.entries.associateWith {
                if (it == OracleMeasure.TTC_FRONT) floatArrayOf(1.0f, inf, inf)
                else FloatArray(3) { inf }
              },
          mutantAccident = BooleanArray(12),
          mutant =
              OracleMeasure.entries.associateWith {
                if (it == OracleMeasure.TTC_FRONT) ttcFront else FloatArray(12) { inf }
              },
          scenarioIds = intArrayOf(100, 101, 102),
          mutantIds = intArrayOf(1, 2, 3, 4),
          aiPilotRow = 3,
      )

  private val matrix = KillMatrices.compute(data, KillOracle.TTC, OracleThresholds())

  /** Scenarios the baseline violates leave the pool and are listed with the avoidance flag. */
  @Test
  fun `baseline-violating scenarios are removed and listed`() {
    assertContentEquals(intArrayOf(101, 102), matrix.poolScenarioIds)
    assertEquals(listOf(BaselineViolation(100, anyMutantAvoided = true)), matrix.baselineViolations)
  }

  /** Mutants with identical kill columns collapse to the smallest id; non-killing ones drop out. */
  @Test
  fun `cleaning drops non-killing mutants and groups duplicates`() {
    assertContentEquals(intArrayOf(1), matrix.mutantIds)
    assertEquals(mapOf(1 to listOf(1, 2)), matrix.duplicateGroups)
    assertEquals(listOf(3), matrix.nonKillingMutantIds)
    assertEquals(BitSet().apply { set(0) }, matrix.kills.single())
  }

  /** AiPilot is kept apart as an outside check and never counts as a mutant. */
  @Test
  fun `AiPilot is an outside check, not a mutant`() {
    assertEquals(BitSet().apply { set(1) }, matrix.aiPilotKills)
  }

  /** A matrix survives being stored and read back unchanged. */
  @Test
  fun `stored matrix round-trips`() {
    val back = KillMatrices.fromStored(KillMatrices.toStored(matrix, OracleThresholds()))
    assertEquals(matrix.oracle, back.oracle)
    assertContentEquals(matrix.poolScenarioIds, back.poolScenarioIds)
    assertContentEquals(matrix.mutantIds, back.mutantIds)
    assertEquals(matrix.kills, back.kills)
    assertEquals(matrix.duplicateGroups, back.duplicateGroups)
    assertEquals(matrix.nonKillingMutantIds, back.nonKillingMutantIds)
    assertEquals(matrix.baselineViolations, back.baselineViolations)
    assertEquals(matrix.aiPilotKills, back.aiPilotKills)
  }

  /** G0 has no thresholds and no accident here, so every mutant is non-killing. */
  @Test
  fun `G0 uses the accident flags only`() {
    val g0 = KillMatrices.compute(data, KillOracle.G0, OracleThresholds())
    assertEquals(3, g0.poolScenarioIds.size)
    assertEquals(listOf(1, 2, 3), g0.nonKillingMutantIds)
  }
}
