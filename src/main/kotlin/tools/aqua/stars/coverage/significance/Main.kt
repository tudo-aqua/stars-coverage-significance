/*
 * Copyright 2025 The STARS OWA Coverage Authors
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

import java.io.File
import java.util.BitSet
import kotlin.random.Random

/**
 * Traffic scenario generator for a 3-lane road with 100 blocks per lane. Each block can contain at
 * most one vehicle (bitmask representation).
 */
object TrafficScenarioGen {
  const val LANES = 3
  const val BLOCKS_PER_LANE = 100
  const val N_BITS = LANES * BLOCKS_PER_LANE // 300

  // Hard-coded lane probabilities: right -> middle -> left
  // Tune as you like; this is a "keep-right"-ish distribution that still uses middle/left.
  val P_LANE = doubleArrayOf(0.45, 0.35, 0.20)

  /** Efficient "sample without replacement" pool for positions 0..(n-1). */
  private class IntPool(n: Int) {
    private val a = IntArray(n) { it }
    var size: Int = n
      private set

    fun isEmpty(): Boolean = size <= 0

    fun drawUniform(rng: Random): Int {
      val i = rng.nextInt(size)
      val v = a[i]
      // swap-remove
      size -= 1
      a[i] = a[size]
      a[size] = v
      return v
    }
  }

  /**
   * Draw a lane index according to probabilities pLane, restricted to lanes that still have
   * capacity.
   */
  private fun drawLaneRestricted(rng: Random, pLane: DoubleArray, pools: Array<IntPool>): Int {
    var sum = 0.0
    for (l in 0 until LANES) {
      if (!pools[l].isEmpty()) sum += pLane[l]
    }
    // sum should be > 0 as long as at least one lane has capacity
    val r = rng.nextDouble() * sum
    var acc = 0.0
    for (l in 0 until LANES) {
      if (pools[l].isEmpty()) continue
      acc += pLane[l]
      if (r <= acc) return l
    }
    // Fallback (shouldn't happen due to numerical issues only)
    for (l in 0 until LANES) if (!pools[l].isEmpty()) return l
    error("No lanes have remaining capacity.")
  }

  /** Generate ONE scenario bitmask with k vehicles. */
  fun sampleScenario(rng: Random, k: Int, pLane: DoubleArray = P_LANE): BitSet {
    val kk = k.coerceIn(0, N_BITS) // hard cap
    val pools = Array(LANES) { IntPool(BLOCKS_PER_LANE) }
    val mask = BitSet(N_BITS)

    repeat(kk) {
      val lane = drawLaneRestricted(rng, pLane, pools)
      val pos = pools[lane].drawUniform(rng)
      val idx = lane * BLOCKS_PER_LANE + pos
      mask.set(idx)
    }
    return mask
  }

  /** Generate x UNIQUE scenarios (BitSets). k is sampled uniformly from [kMin, kMax]. */
  fun generateUniqueScenarios(
      x: Int,
      kMin: Int,
      kMax: Int,
      seed: Int? = null,
      pLane: DoubleArray = P_LANE
  ): List<BitSet> {
    require(x > 0) { "x must be > 0" }
    require(kMin <= kMax) { "kMin must be <= kMax" }

    val rng = if (seed != null) Random(seed) else Random.Default

    val seen = HashSet<BitSet>(x * 2)
    val out = ArrayList<BitSet>(x)

    while (out.size < x) {
      val k = rng.nextInt(kMax - kMin + 1) + kMin
      val m = sampleScenario(rng, k, pLane)
      // BitSet is mutable; we won't mutate m further, so it's safe to store directly.
      if (seen.add(m)) out.add(m)
    }
    return out
  }

  /**
   * Decode a bitmask into a list of (lane, block) coordinates. lane in [0..2] (right->left by
   * convention), block in [0..99] along the road.
   */
  fun bitmapToCoordinates(mask: BitSet): List<Pair<Int, Int>> {
    val coords = ArrayList<Pair<Int, Int>>(mask.cardinality())
    var i = mask.nextSetBit(0)
    while (i >= 0) {
      val lane = i / BLOCKS_PER_LANE
      val block = i % BLOCKS_PER_LANE
      coords.add(lane to block)
      i = mask.nextSetBit(i + 1)
    }
    return coords
  }

  /**
   * Create TikZ code for ONE scenario: base checkerboard road + colored spawn boxes.
   *
   * Note on coordinates:
   * - x = block index (0..99)
   * - y = lane index (0..2), lane 0 drawn at bottom row.
   */
  fun scenarioToTikz(
      mask: BitSet,
      laneHeightCm: Double = 0.8,
      spawnStyle: String = "fill=red!70,opacity=0.85",
      showLabels: Boolean = true
  ): String {
    val coords = bitmapToCoordinates(mask)

    // Build a TikZ-friendly list: "block/lane,block/lane,..."
    // TikZ uses (x,y) = (block, lane)
    val spawnList = coords.joinToString(",") { (lane, block) -> "${block}/${lane}" }

    val labels =
        if (showLabels) {
          """
            \node[below, anchor=west, inner sep=0pt,yshift=-6pt] at (0,0) {0 km};
            \node[below, anchor=east, inner sep=0pt,yshift=-6pt] at (\linewidth,0) {10 km};
            """
              .trimIndent()
        } else ""

    return """
        \begin{tikzpicture}[line join=round]

        % Hard-coded: 3 lanes, 100 blocks per lane
        \def\nLanes{3}
        \def\nBlocks{100}

        % Fit road width to \linewidth while keeping text unscaled
        \newlength{\RoadXUnit}
        \setlength{\RoadXUnit}{\dimexpr\linewidth/\nBlocks\relax}

        \begin{scope}[x=\RoadXUnit, y=${laneHeightCm}cm]

          % Checkerboard base
          \foreach \i in {0,...,\numexpr\nBlocks-1\relax}{
            \foreach \j in {0,...,\numexpr\nLanes-1\relax}{
              \pgfmathtruncatemacro{\p}{mod(\i+\j,2)}
              \ifnum\p=0
                \fill[gray!70] (\i,\j) rectangle ++(1,1);
              \else
                \fill[gray!80] (\i,\j) rectangle ++(1,1);
              \fi
              \draw[black!40, line width=0.15pt] (\i,\j) rectangle ++(1,1);
            }
          }

          % Spawn overlays (selected cells)
          \foreach \x/\y in {$spawnList}{
            \path[$spawnStyle] (\x,\y) rectangle ++(1,1);
          }

          % Road outline
          % \draw[black, very thick] (0,0) rectangle (\nBlocks,\nLanes);

          % Dashed lane separators
          \foreach \k in {1,...,\numexpr\nLanes-1\relax}{
            \draw[white, line width=0.6pt, dashed] (0,\k) -- (\nBlocks,\k);
          }

        \end{scope}

        $labels

        \end{tikzpicture}
        """
        .trimIndent()
  }

  /** Convenience: write a scenario's TikZ code to a .tex snippet file. */
  fun writeTikzToFile(mask: BitSet, file: File) {
    file.writeText(scenarioToTikz(mask))
  }
}

/** Example usage */
fun main() {
  val x = 1 // e.g. 10k..100k in your experiment
  val kMin = 200
  val kMax = 200

  val scenarios =
      TrafficScenarioGen.generateUniqueScenarios(
          x = x, kMin = kMin, kMax = kMax, seed = Random.nextInt())

  // Pick one scenario and export to TikZ
  val one = scenarios.first()
  val tikz = TrafficScenarioGen.scenarioToTikz(one, spawnStyle = "fill=blue!70,opacity=0.85")
  println(tikz)

  // Or write to file:
  // TrafficScenarioGen.writeTikzToFile(one, File("scenario.tex"))
}
