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
import tools.aqua.stars.coverage.significance.postEvaluation.DrawTicksWithDecisionTreeGroupingPostEvaluation.HitCountingPool

/**
 * Tests for [DrawTicksWithDecisionTreeGroupingPostEvaluation.SharedDrawPool], which draws distinct
 * elements without replacement from a shared list without copying it. [bruteForceDraw] is a literal
 * copy-and-swap-remove implementation of the same draw-without-replacement process, used here as
 * the reference [SharedDrawPool] must reproduce exactly, draw for draw, for the same seed.
 */
class DrawTicksWithDecisionTreeGroupingPostEvaluationTest {

  /**
   * Copies [source] into a private list, then repeatedly swap-removes a uniformly random element
   * from it - `O(n)` space per call, but a faithful, obviously-correct reference for what drawing
   * without replacement means.
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

  // --------------------------------------------------------------------------- HitCountingPool

  /**
   * Unlike [SharedDrawPool] (checked against [bruteForceDraw] above via exact draw-sequence
   * equality), [HitCountingPool] is not held to that same bar: a literal swap-remove's notion of
   * "which index holds a marked item" is path-dependent (it depends on exactly which concrete
   * values previous draws happened to swap into which slot), whereas [HitCountingPool] always
   * compares the raw draw against a canonical "marked items occupy the front" view. Both are
   * unbiased, correct ways to sample the same hypergeometric process, but only *distributionally*
   * equivalent - not bit-for-bit reproducible from shared randomness. So [HitCountingPool] is
   * checked here via its distribution (single-draw hit rate, and the closed-form time-to-first-hit
   * mean) and via the one property that *is* exact regardless of path: the total hit count over a
   * full drain.
   */
  @Test
  fun `single-draw hit rate matches hitCount over poolSize`() {
    val poolSize = 1_000L
    val hitCount = 37L
    val trials = 50_000
    var hits = 0
    for (seed in 1..trials) {
      if (HitCountingPool(poolSize, hitCount).drawIsHit(Random(seed))) hits++
    }
    val rate = hits.toDouble() / trials
    val expected = hitCount.toDouble() / poolSize
    assertTrue(kotlin.math.abs(rate - expected) < 0.01, "empirical rate $rate, expected ~$expected")
  }

  /**
   * The expected number of draws until a pool of [n] items with [k] hits first reports a hit has
   * the closed form `(n + 1) / (k + 1)` - the expected rank of the first "success" in a uniformly
   * random permutation. Checked empirically over many seeds.
   */
  @Test
  fun `expected draws-until-first-hit matches the closed-form negative-hypergeometric mean`() {
    val n = 2_000L
    val k = 40L
    val expected = (n + 1).toDouble() / (k + 1).toDouble()
    val trials = 20_000

    var totalDraws = 0L
    for (seed in 1..trials) {
      val pool = HitCountingPool(n, k)
      val rng = Random(seed)
      var draws = 0L
      while (true) {
        draws++
        if (pool.drawIsHit(rng)) break
      }
      totalDraws += draws
    }
    val sampleMean = totalDraws.toDouble() / trials

    val tolerance = expected * 0.10
    assertTrue(
        kotlin.math.abs(sampleMean - expected) < tolerance,
        "sample mean $sampleMean was not within $tolerance of expected $expected")
  }

  /** Over a full drain, exactly [hitCount] draws report a hit - no more, no fewer. */
  @Test
  fun `draining a HitCountingPool reports exactly hitCount hits in total`() {
    for ((poolSize, hitCount) in listOf(0 to 0, 1 to 1, 50 to 0, 50 to 50, 500 to 13)) {
      val pool = HitCountingPool(poolSize.toLong(), hitCount.toLong())
      val rng = Random(42)
      var hits = 0
      var draws = 0
      while (!pool.isEmpty) {
        draws++
        if (pool.drawIsHit(rng)) hits++
      }
      assertEquals(poolSize, draws, "poolSize $poolSize, hitCount $hitCount")
      assertEquals(hitCount, hits, "poolSize $poolSize, hitCount $hitCount")
    }
  }

  /** Drawing from an exhausted pool is a programming error, not a silent no-op. */
  @Test
  fun `drawIsHit throws once the pool is exhausted`() {
    val pool = HitCountingPool(1L, 1L)
    val rng = Random(1)
    assertTrue(pool.drawIsHit(rng))
    assertTrue(pool.isEmpty)
    assertFailsWith<IllegalStateException> { pool.drawIsHit(rng) }
  }

  /** A zero-size pool starts out exhausted. */
  @Test
  fun `a zero-size HitCountingPool is immediately exhausted`() {
    assertTrue(HitCountingPool(0L, 0L).isEmpty)
  }
}
