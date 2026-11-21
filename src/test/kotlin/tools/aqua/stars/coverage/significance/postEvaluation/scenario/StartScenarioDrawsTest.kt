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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the draw orders of [StartScenarioDraws] on a toy pool. */
class StartScenarioDrawsTest {

  private fun bits(vararg p: Int) = BitSet().apply { p.forEach { set(it) } }

  private fun assertPermutation(n: Int, order: IntArray) =
      assertContentEquals(IntArray(n) { it }, order.sortedArray())

  /** Greedy takes the most new kills, ties by position, and resets once all are killed. */
  @Test
  fun `greedy order is exact`() {
    // Positions 0..5; mutant A killed by 1, 2; B by 2, 4; C by 5.
    val kills = listOf(bits(1, 2), bits(2, 4), bits(5))
    // 2 kills A and B; 5 kills C; all killed -> reset: 1 (A), 4 (B), then nothing adds a kill
    // for C -> reset -> no position adds a kill -> the rest by position.
    assertContentEquals(intArrayOf(2, 5, 1, 4, 0, 3), StartScenarioDraws.greedyOrder(kills, 6))
  }

  /** Every grouped order draws every position exactly once (D10). */
  @Test
  fun `grouped orders are permutations of the pool`() {
    val groupOf = intArrayOf(0, 0, 1, 2, 2, 2, 1, 0, 3)
    repeat(20) { seed ->
      assertPermutation(9, StartScenarioDraws.groupedEqualOrder(groupOf, 4, Random(seed)))
      assertPermutation(
          9,
          StartScenarioDraws.groupedWeightedOrder(
              groupOf, doubleArrayOf(0.5, 0.0, 1.0, 0.0), Random(seed)))
      assertPermutation(9, StartScenarioDraws.randomOrder(9, Random(seed)))
    }
  }

  /** The first round of the equal strategy takes one position from each group. */
  @Test
  fun `equal strategy cycles over the groups`() {
    val groupOf = intArrayOf(0, 0, 0, 1, 2, 2)
    val first = StartScenarioDraws.groupedEqualOrder(groupOf, 3, Random(1)).take(3)
    assertEquals(setOf(0, 1, 2), first.map { groupOf[it] }.toSet())
  }

  /** Positive-weight groups come first; the zero-weight ones follow, so all are drawn. */
  @Test
  fun `weighted strategy continues after the positive groups are empty`() {
    val groupOf = intArrayOf(0, 1, 1, 2, 0, 1)
    val order =
        StartScenarioDraws.groupedWeightedOrder(groupOf, doubleArrayOf(0.0, 2.0, 0.0), Random(3))
    assertEquals(listOf(1, 1, 1), order.take(3).map { groupOf[it] })
    assertPermutation(6, order)
  }

  /** Class weights are kill rates on the training mutants. */
  @Test
  fun `group weights are kill rates`() {
    val groupOf = intArrayOf(0, 0, 1, 1)
    val w = StartScenarioDraws.groupWeights(groupOf, 2, listOf(bits(0, 2), bits(0)))
    assertEquals(listOf(0.5, 0.25), w.toList())
  }

  /** With random orders the mean time to kill is (N + 1) / (K + 1). */
  @Test
  fun `random time to kill matches the expectation`() {
    val n = 200
    val kills = listOf(bits(3, 50, 120, 199))
    val mean =
        (0 until 4000)
            .map {
              StartScenarioDraws.timeToKill(StartScenarioDraws.randomOrder(n, Random(it)), kills)[0]
            }
            .average()
    val expected = (n + 1) / 5.0
    assertTrue(kotlin.math.abs(mean - expected) < 2.0, "mean $mean, expected $expected")
  }

  /** Time to kill is 1 + the first position of a killing scenario; -1 if none. */
  @Test
  fun `time to kill is a lookup`() {
    val order = intArrayOf(4, 2, 0, 1, 3)
    assertContentEquals(
        intArrayOf(2, 1, -1),
        StartScenarioDraws.timeToKill(order, listOf(bits(2, 3), bits(4), BitSet())))
  }
}
