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

package tools.aqua.stars.coverage.significance

import kotlin.random.Random

object TrafficScenarioGenSingleMaskReadable {

  enum class VehicleType(val sumoId: String) {
    TRUCK("truck"),
    CAR_CALM("car_calm"),
    CAR_NORMAL("car_normal"),
    CAR_SPORTY("car_sporty")
  }

  data class Config(
      val x: Int,
      val kMin: Int,
      val kMax: Int,
      val nL: Int = 3,
      val nP: Int = 100,
      val types: List<VehicleType> = VehicleType.entries.toList(),

      // q=(0.2,0.4,0.3,0.1) in the same order as `types`
      val qType: DoubleArray = doubleArrayOf(0.2, 0.4, 0.3, 0.1),

      // p^(t) in lane order: right(0) -> middle(1) -> left(2)
      val pLaneByType: Map<VehicleType, DoubleArray> =
          mapOf(
              VehicleType.TRUCK to doubleArrayOf(0.70, 0.30, 0.00),
              VehicleType.CAR_CALM to doubleArrayOf(0.33, 0.34, 0.33),
              VehicleType.CAR_NORMAL to doubleArrayOf(0.33, 0.34, 0.33),
              VehicleType.CAR_SPORTY to doubleArrayOf(0.00, 0.40, 0.60),
          ),
      val seed: Int? = null
  )

  data class Scenario(
      val mask: Array<VehicleType?>, // null = empty, else vehicle type
      val nL: Int,
      val nP: Int
  ) {
    fun n(): Int = nL * nP

    fun vehiclesCount(): Int = mask.count { it != null }
  }

  data class Spawn(val type: VehicleType, val lane: Int, val area: Int, val idx: Int)

  private fun validate(cfg: Config) {
    require(cfg.x > 0) { "x must be > 0" }
    require(cfg.kMin <= cfg.kMax) { "kMin must be <= kMax" }
    require(cfg.nL > 0 && cfg.nP > 0) { "nL and nP must be > 0" }
    require(cfg.qType.size == cfg.types.size) { "qType must match number of types" }
    require(cfg.qType.all { it >= 0.0 } && cfg.qType.sum() > 0.0) {
      "qType must be non-negative and not all zero"
    }
    cfg.types.forEach { t ->
      val p = cfg.pLaneByType[t] ?: error("Missing lane probabilities for type $t")
      require(p.size == cfg.nL) { "pLane($t) must have length nL=${cfg.nL}" }
      require(p.all { it >= 0.0 } && p.sum() > 0.0) {
        "pLane($t) must be non-negative and not all zero"
      }
    }
  }

  private fun categoricalIndex(rng: Random, probs: DoubleArray): Int {
    val sum = probs.sum()
    val r = rng.nextDouble() * sum
    var acc = 0.0
    for (i in probs.indices) {
      acc += probs[i]
      if (r <= acc) return i
    }
    return probs.size - 1
  }

  private fun chooseLane(rng: Random, pLane: DoubleArray, lanesWithCapacity: BooleanArray): Int {
    // restrict to lanes that still have free spawn areas AND have p>0
    val restricted =
        DoubleArray(pLane.size) { i ->
          if (lanesWithCapacity[i] && pLane[i] > 0.0) pLane[i] else 0.0
        }
    if (restricted.sum() <= 0.0) return -1
    return categoricalIndex(rng, restricted)
  }

  fun sampleOne(cfg: Config, rng: Random, k: Int): Scenario {
    val n = cfg.nL * cfg.nP
    val mask: Array<VehicleType?> = arrayOfNulls(n)

    // Available spawn areas per lane (readable version: MutableList)
    val availablePerLane: Array<MutableList<Int>> =
        Array(cfg.nL) { lane -> (0 until cfg.nP).toMutableList() }

    val kk = k.coerceIn(0, n)

    repeat(kk) {
      // 1) choose vehicle type
      val typeIdx = categoricalIndex(rng, cfg.qType)
      val type = cfg.types[typeIdx]

      // 2) choose lane allowed for this type and with remaining capacity
      val lanesWithCapacity = BooleanArray(cfg.nL) { l -> availablePerLane[l].isNotEmpty() }
      val lane = chooseLane(rng, cfg.pLaneByType.getValue(type), lanesWithCapacity)

      if (lane < 0) {
        // No allowed lane has capacity (rare unless you get near-full lanes).
        // For readability, we just skip this placement attempt.
        return@repeat
      }

      // 3) choose spawn area uniformly within lane, without replacement
      val list = availablePerLane[lane]
      val pickedIndex = rng.nextInt(list.size)
      val area = list.removeAt(pickedIndex)

      val idx = lane * cfg.nP + area
      mask[idx] = type
    }

    return Scenario(mask = mask, nL = cfg.nL, nP = cfg.nP)
  }

  fun generate(cfg: Config): List<Scenario> {
    validate(cfg)
    val rng = if (cfg.seed != null) Random(cfg.seed) else Random.Default

    val out = ArrayList<Scenario>(cfg.x)

    // Readable uniqueness key: snapshot List<VehicleType?>
    val seen = HashSet<List<VehicleType?>>(cfg.x * 2)

    while (out.size < cfg.x) {
      val k = rng.nextInt(cfg.kMax - cfg.kMin + 1) + cfg.kMin
      val scenario = sampleOne(cfg, rng, k)

      val key: List<VehicleType?> = scenario.mask.toList()
      if (seen.add(key)) out.add(scenario)
    }

    return out
  }

  fun toCoordinates(s: Scenario): List<Spawn> {
    val out = ArrayList<Spawn>(s.vehiclesCount())
    for (idx in s.mask.indices) {
      val t = s.mask[idx] ?: continue
      val lane = idx / s.nP
      val area = idx % s.nP
      out.add(Spawn(type = t, lane = lane, area = area, idx = idx))
    }
    return out
  }

  fun toTikz(
      s: Scenario,
      laneHeightCm: Double = 0.8,
      showLabels: Boolean = true,
      tikzFillByType: Map<VehicleType, String> =
          mapOf(
              VehicleType.TRUCK to "fill=orange!75,opacity=0.85",
              VehicleType.CAR_CALM to "fill=blue!60,opacity=0.80",
              VehicleType.CAR_NORMAL to "fill=green!60,opacity=0.80",
              VehicleType.CAR_SPORTY to "fill=red!70,opacity=0.85",
          )
  ): String {

    fun listForType(type: VehicleType): String {
      val items = mutableListOf<String>()
      for (idx in s.mask.indices) {
        if (s.mask[idx] == type) {
          val lane = idx / s.nP
          val area = idx % s.nP
          items.add("${area}/${lane}") // (x,y)=(block,lane)
        }
      }
      return items.joinToString(",")
    }

    val overlays =
        buildString {
              for (t in VehicleType.entries) {
                val list = listForType(t)
                if (list.isBlank()) continue
                val style = tikzFillByType[t] ?: "fill=black!60,opacity=0.7"
                appendLine(
                    """  \foreach \x/\y in {$list} { \path[$style] (\x,\y) rectangle ++(1,1); }""")
              }
            }
            .trimEnd()

    val labels =
        if (showLabels) {
          """
            \node[below, anchor=west, inner sep=0pt, yshift=-6pt] at (0,0) {0 km};
            \node[below, anchor=east, inner sep=0pt, yshift=-6pt] at (\linewidth,0) {10 km};
            """
              .trimIndent()
        } else ""

    return """
        \begin{tikzpicture}[line join=round]
        \def\nLanes{${s.nL}}
        \def\nBlocks{${s.nP}}

        \newlength{\RoadXUnit}
        \setlength{\RoadXUnit}{\dimexpr\linewidth/\nBlocks\relax}

        \begin{scope}[x=\RoadXUnit, y=${laneHeightCm}cm]

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

$overlays

          % \draw[black, very thick] (0,0) rectangle (\nBlocks,\nLanes);

          \foreach \k in {1,...,\numexpr\nLanes-1\relax}{
            \draw[white, line width=0.6pt, dashed] (0,\k) -- (\nBlocks,\k);
          }

        \end{scope}

        $labels
        \end{tikzpicture}
        """
        .trimIndent()
  }
}

/** Example usage with your configured values */
fun main() {
  val cfg =
      TrafficScenarioGenSingleMaskReadable.Config(
          x = 10_000,
          kMin = 200,
          kMax = 200,
          nL = 3,
          nP = 100,
          qType = doubleArrayOf(0.2, 0.4, 0.3, 0.1),
          pLaneByType =
              mapOf(
                  TrafficScenarioGenSingleMaskReadable.VehicleType.TRUCK to
                      doubleArrayOf(0.70, 0.30, 0.00),
                  TrafficScenarioGenSingleMaskReadable.VehicleType.CAR_CALM to
                      doubleArrayOf(0.33, 0.34, 0.33),
                  TrafficScenarioGenSingleMaskReadable.VehicleType.CAR_NORMAL to
                      doubleArrayOf(0.33, 0.34, 0.33),
                  TrafficScenarioGenSingleMaskReadable.VehicleType.CAR_SPORTY to
                      doubleArrayOf(0.00, 0.40, 0.60),
              ),
          seed = 4)

  val scenarios = TrafficScenarioGenSingleMaskReadable.generate(cfg)

  val first = scenarios.first()
  //  println("vehicles=${first.vehiclesCount()}")
  //  println(TrafficScenarioGenSingleMaskReadable.toCoordinates(first).take(5))

  val tikz = TrafficScenarioGenSingleMaskReadable.toTikz(first)
  println(tikz)
}
