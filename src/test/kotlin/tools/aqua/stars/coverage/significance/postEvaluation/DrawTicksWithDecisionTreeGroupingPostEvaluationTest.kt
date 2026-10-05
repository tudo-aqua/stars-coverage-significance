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

package tools.aqua.stars.coverage.significance.postEvaluation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool]: the replacement for
 * copying a leaf's (or the full pool's) tick list into a private `MutableList` per repetition
 * before swap-removing from it. The whole point of [SharedDrawPool] is that it must draw *the same
 * real ticks, in the same order*, as that literal copy-and-swap-remove approach did - just without
 * paying for the copy. [bruteForceDraw] is that literal original approach, kept here as the
 * reference it's being compared against.
 */
class DrawTicksWithDecisionTreeGroupingPostEvaluationTest {

  /**
   * The original, pre-optimization approach: copy [source] into a private list, then repeatedly
   * swap-remove a uniformly random element from it. `O(n)` space per call - exactly the cost
   * [SharedDrawPool] exists to avoid - but a faithful, obviously-correct reference for what "really
   * drawing ticks without replacement" means.
   */
  private fun <T> bruteForceDraw(source: List<T>, draws: Int, rng: Random): List<T> {
    val remaining = source.toMutableList()
    return (1..draws).map {
      val idx = rng.nextInt(remaining.size)
      val value = remaining[idx]
      remaining[idx] = remaining[remaining.size - 1]
      remaining.removeAt(remaining.size - 1)
      value
    }
  }

  /**
   * For many sizes and seeds, draining a [SharedDrawPool] must reproduce *exactly* the same
   * sequence of elements, in exactly the same order, as [bruteForceDraw] draining a real copy of
   * the same source list with an identically-seeded [Random] - not just a statistically similar
   * one. Both algorithms issue the same sequence of `rng.nextInt(remaining)` calls against the same
   * shrinking `remaining` count, so this is an exact-equality check, not a tolerance-based one.
   */
  @Test
  fun `draining a SharedDrawPool reproduces the exact same draw sequence as copying and swap-removing`() {
    for (size in listOf(0, 1, 2, 5, 37, 500)) {
      val source = (0 until size).map { "tick-$it" }
      for (seed in 1..30) {
        val expected = bruteForceDraw(source, size, Random(seed))

        val pool = DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool(source)
        val rng = Random(seed)
        val actual = mutableListOf<String>()
        while (!pool.isEmpty()) {
          actual.add(pool.drawAndRemoveRandomTick(rng))
        }

        assertEquals(expected, actual, "size $size, seed $seed")
      }
    }
  }

  /** Every element of the source list is drawn exactly once - draws without replacement. */
  @Test
  fun `every source element is drawn exactly once`() {
    val source = (0 until 200).map { it }
    val pool = DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool(source)
    val rng = Random(99)
    val drawn = mutableListOf<Int>()
    while (!pool.isEmpty()) {
      drawn.add(pool.drawAndRemoveRandomTick(rng))
    }
    assertEquals(source.toSet(), drawn.toSet())
    assertEquals(source.size, drawn.size)
  }

  /** Drawing from an exhausted pool is a programming error, not a silent no-op. */
  @Test
  fun `drawAndRemoveRandomTick throws once the pool is exhausted`() {
    val pool = DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool(listOf("only"))
    val rng = Random(1)
    assertEquals("only", pool.drawAndRemoveRandomTick(rng))
    assertTrue(pool.isEmpty())
    assertFailsWith<IllegalStateException> { pool.drawAndRemoveRandomTick(rng) }
  }

  /** An empty source list starts out exhausted. */
  @Test
  fun `a pool over an empty source is immediately exhausted`() {
    val pool = DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool(emptyList<Int>())
    assertTrue(pool.isEmpty())
  }

  /**
   * A partial draw (fewer draws than the pool size, as every real caller does - a suite size or a
   * stop-at-first-kill) must also match the brute-force reference exactly, not just a full drain.
   */
  @Test
  fun `a partial draw matches the brute-force reference exactly`() {
    val source = (0 until 10_000).map { it }
    for (seed in 1..20) {
      val expected = bruteForceDraw(source, 50, Random(seed))

      val pool = DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool(source)
      val rng = Random(seed)
      val actual = (1..50).map { pool.drawAndRemoveRandomTick(rng) }

      assertEquals(expected, actual, "seed $seed")
    }
  }
}
