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
import kotlin.test.assertEquals

/**
 * Tests for [KillRarity] on a hand-built [KillMatrix] of four scenarios and two kept mutants:
 * mutant 1 (kept for itself and mutant 2) is killed in scenarios 0 and 1, mutant 3 only in
 * scenario 3.
 */
class KillRarityTest {

  private val matrix =
      KillMatrix(
          oracle = KillOracle.TG,
          poolScenarioIds = intArrayOf(10, 11, 12, 13),
          mutantIds = intArrayOf(1, 3),
          kills = listOf(bits(0, 1), bits(3)),
          duplicateGroups = mapOf(1 to listOf(1, 2)),
          nonKillingMutantIds = listOf(4),
          baselineViolations = emptyList(),
          aiPilotKills = null,
      )

  private val rarity = KillRarity.compute(matrix, scenarioCount = 5, mutantCount = 4)

  private fun bits(vararg on: Int) = BitSet().apply { on.forEach { set(it) } }

  /** Quantiles come from drawing without replacement: 1 of 2 scenarios kills, so half need 2. */
  @Test
  fun `draw quantiles are exact for drawing without replacement`() {
    assertEquals(1, KillRarity.drawQuantile(2, 1, 0.5))
    assertEquals(2, KillRarity.drawQuantile(2, 1, 0.95))
    assertEquals(1, KillRarity.drawQuantile(10, 10, 0.95))
    // 1 of 4 kills: P(killed within d) = d / 4.
    assertEquals(3, KillRarity.drawQuantile(4, 1, 0.75))
  }

  /** Two scenarios (one of 0/1, plus 3) kill both kept mutants. */
  @Test
  fun `greedy cover needs one scenario per disjoint kill set`() {
    assertEquals(2, KillRarity.greedyCover(matrix))
    assertEquals(2, rarity.greedyMinimum)
  }

  /** Duplicates count in every per-mutant number; non-killing mutants only in the total. */
  @Test
  fun `rows and counts include duplicates`() {
    assertEquals(listOf(3, 1, 2), rarity.mutantRows.map { it.mutantId })
    assertEquals(3, rarity.killing)
    assertEquals(2, rarity.distinct)
    assertEquals(1, rarity.removed)
    assertEquals(
        listOf(KillsPerScenario(0, 1), KillsPerScenario(1, 1), KillsPerScenario(2, 2)),
        rarity.killsPerScenario)
  }

  /** All draws are needed at the latest, and the curve reaches 1 at the full pool. */
  @Test
  fun `drawing the whole pool kills everything`() {
    val all = rarity.drawsToKillAll!!
    assertEquals(true, all.p95 <= 4)
    assertEquals(1.0, rarity.curve.last().expectedShareKilled, 1e-12)
    assertEquals(1.0, rarity.curve.last().pAllKilled, 1e-12)
  }
}
