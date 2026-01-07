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

package tools.aqua.stars.coverage.significance.fullTrafficGenerator

import kotlin.random.Random

/**
 * Generator for synthetic highway traffic placement scenarios.
 *
 * The fullTrafficGenerator produces *unique* scenarios that place vehicles of different
 * [FullTrafficVehicleType]s on a discretized road:
 * - the road has [numberOfLanes] lanes
 * - each lane is divided into [numberOfBlocksPerLane] discrete longitudinal "areas" (blocks)
 *
 * Each scenario is represented as a single 1D mask ([GeneratedScenarioFullTraffic.mask]) of length
 * `[numberOfLanes] * [numberOfBlocksPerLane]`. The index is computed as:
 *
 * `index = lane * [numberOfBlocksPerLane] + area`
 *
 * where:
 * - `lane` is the lane index (0..[numberOfLanes]-1)
 * - `area` is the longitudinal area index (0..[numberOfBlocksPerLane]-1)
 *
 * @property scenarioCount Number of *unique* scenarios to generate.
 * @property minNumOfVehicles Minimum number of vehicles placed into a scenario.
 * @property maxNumOfVehicles Maximum number of vehicles placed into a scenario.
 * @property numberOfLanes Number of lanes.
 * @property numberOfBlocksPerLane Number of longitudinal areas (blocks) per lane.
 * @property fullTrafficVehicleTypes Ordered list of vehicle types; this order must match
 *   [distributionOfVehicleTypes].
 * @property distributionOfVehicleTypes Relative probabilities of sampling each vehicle type
 *   (categorical distribution). Must have the same length/order as [fullTrafficVehicleTypes].
 *   Values do not need to sum to 1.0.
 * @property probabilityOfLaneByFullTrafficVehicleType Lane-choice probabilities per type. Each
 *   array must have length [numberOfLanes]. Values do not need to sum to 1.0.
 * @property seed Optional RNG seed for reproducible scenario generation.
 */
data class FullTrafficScenarioGenerator(
    val scenarioCount: Int,
    val minNumOfVehicles: Int,
    val maxNumOfVehicles: Int,
    val numberOfLanes: Int = 3,
    val numberOfBlocksPerLane: Int = 100,
    val fullTrafficVehicleTypes: List<FullTrafficVehicleType> =
        FullTrafficVehicleType.entries.toList(),
    val distributionOfVehicleTypes: DoubleArray = doubleArrayOf(0.2, 0.4, 0.3, 0.1),

    // p^(t) in lane order: right(0) -> middle(1) -> left(2)
    val probabilityOfLaneByFullTrafficVehicleType: Map<FullTrafficVehicleType, DoubleArray> =
        mapOf(
            FullTrafficVehicleType.TRUCK to doubleArrayOf(0.70, 0.30, 0.00),
            FullTrafficVehicleType.CAR_CALM to doubleArrayOf(0.33, 0.34, 0.33),
            FullTrafficVehicleType.CAR_NORMAL to doubleArrayOf(0.33, 0.34, 0.33),
            FullTrafficVehicleType.CAR_SPORTY to doubleArrayOf(0.00, 0.40, 0.60),
        ),
    val seed: Int? = null
) {

  /**
   * Validates parameters and fails fast if it is inconsistent.
   *
   * @throws IllegalArgumentException if the configuration violates constraints.
   */
  private fun validate() {
    require(scenarioCount > 0) { "Scenario count must be > 0" }
    require(minNumOfVehicles <= maxNumOfVehicles) { "Minimum number of vehicles must be <= kMax" }
    require(numberOfLanes > 0 && numberOfBlocksPerLane > 0) {
      "Number of Lanes and number of blocks per lane must be > 0"
    }
    require(distributionOfVehicleTypes.size == fullTrafficVehicleTypes.size) {
      "Distribution of vehicle types must match number of types"
    }
    require(
        distributionOfVehicleTypes.all { it >= 0.0 } && distributionOfVehicleTypes.sum() > 0.0) {
          "Distribution of vehicle types per must be non-negative and not all zero"
        }
    fullTrafficVehicleTypes.forEach { vehicleType ->
      val probabilitiesForVehicleType =
          probabilityOfLaneByFullTrafficVehicleType[vehicleType]
              ?: error("Missing lane probabilities for type $vehicleType")
      require(probabilitiesForVehicleType.size == numberOfLanes) {
        "probabilityOfLaneByVehicleType($vehicleType) must have length numberOfLanes=${numberOfLanes}"
      }
      require(
          probabilitiesForVehicleType.all { it >= 0.0 } &&
              probabilitiesForVehicleType.sum() > 0.0) {
            "probabilityOfLaneByVehicleType($vehicleType) must be non-negative and not all zero"
          }
    }
  }

  /**
   * Samples an index from a categorical distribution.
   *
   * The probability mass is given by [probabilities]. The values do not need to sum to 1.0.
   *
   * @param rng Random source.
   * @param probabilities Non-negative weights.
   * @return Sampled index in `probs.indices`.
   */
  private fun categoricalIndex(rng: Random, probabilities: DoubleArray): Int {
    val sum = probabilities.sum()
    val randomValue = rng.nextDouble() * sum
    var cumulativeProbability = 0.0
    for (index in probabilities.indices) {
      cumulativeProbability += probabilities[index]
      if (randomValue <= cumulativeProbability) return index
    }
    return probabilities.size - 1
  }

  /**
   * Chooses a lane based on lane probabilities [probabilityOfLaneByVehicleType], but restricts the
   * choice to lanes that still have available capacity ([lanesWithCapacity]) and have `p > 0`.
   *
   * @param rng Random source.
   * @param probabilityOfLaneByVehicleType Lane probabilities for a specific vehicle type.
   * @param lanesWithCapacity Boolean mask indicating if a lane still has free spawn areas.
   * @return The chosen lane index or `-1` if no lane can be selected.
   */
  private fun chooseLane(
      rng: Random,
      probabilityOfLaneByVehicleType: DoubleArray,
      lanesWithCapacity: BooleanArray
  ): Int {
    // restrict to lanes that still have free spawn areas AND have probabilityOfLaneByVehicleType>0
    val restricted =
        DoubleArray(probabilityOfLaneByVehicleType.size) { i ->
          if (lanesWithCapacity[i] && probabilityOfLaneByVehicleType[i] > 0.0)
              probabilityOfLaneByVehicleType[i]
          else 0.0
        }
    if (restricted.sum() <= 0.0) return -1
    return categoricalIndex(rng, restricted)
  }

  /**
   * Samples a single [GeneratedScenarioFullTraffic] by placing up to [desiredNumberOfVehicles]
   * vehicles (without replacement per lane area).
   *
   * The algorithm:
   * 1) samples a vehicle type according to [distributionOfVehicleTypes]
   * 2) samples a lane according to [probabilityOfLaneByFullTrafficVehicleType] for that type,
   *    restricted to lanes with remaining capacity
   * 3) samples a free area uniformly within that lane (without replacement)
   *
   * @param rng Random source.
   * @param desiredNumberOfVehicles Desired number of vehicles to attempt placing.
   * @return A generated [GeneratedScenarioFullTraffic]. May contain fewer than `k` vehicles if
   *   lanes run out of capacity.
   */
  fun sampleOne(rng: Random, desiredNumberOfVehicles: Int): GeneratedScenarioFullTraffic {
    val totalBlocks = numberOfLanes * numberOfBlocksPerLane
    val mask: Array<FullTrafficVehicleType?> = arrayOfNulls(totalBlocks)

    val availableSpawnAreasPerLane: Array<MutableList<Int>> =
        Array(numberOfLanes) { (0 until numberOfBlocksPerLane).toMutableList() }

    val numberOfVehicles = desiredNumberOfVehicles.coerceIn(0, totalBlocks)

    repeat(numberOfVehicles) {
      // 1) choose vehicle type
      val typeIdx = categoricalIndex(rng, distributionOfVehicleTypes)
      val type = fullTrafficVehicleTypes[typeIdx]

      // 2) choose lane allowed for this type and with remaining capacity
      val lanesWithCapacity =
          BooleanArray(numberOfLanes) { l -> availableSpawnAreasPerLane[l].isNotEmpty() }
      val lane =
          chooseLane(
              rng, probabilityOfLaneByFullTrafficVehicleType.getValue(type), lanesWithCapacity)

      if (lane < 0) {
        // No allowed lane has capacity (rare unless you get near-full lanes).
        // For readability, we just skip this placement attempt.
        return@repeat
      }

      // 3) choose spawn area uniformly within lane, without replacement
      val availableSpawnAreas = availableSpawnAreasPerLane[lane]
      val pickedIndex = rng.nextInt(availableSpawnAreas.size)
      val area = availableSpawnAreas.removeAt(pickedIndex)

      val idx = lane * numberOfBlocksPerLane + area
      mask[idx] = type
    }

    return GeneratedScenarioFullTraffic(
        mask = mask, numberOfLanes = numberOfLanes, numberOfBlocksPerLane = numberOfBlocksPerLane)
  }

  /**
   * Generates [scenarioCount] unique scenarios.
   *
   * Uniqueness is determined by the full [GeneratedScenarioFullTraffic.mask] content. Internally,
   * we store each generated mask as a `List<VehicleType?>` key in a [HashSet].
   *
   * @return A list of unique scenarios of size [scenarioCount].
   */
  fun generate(): List<GeneratedScenarioFullTraffic> {
    validate()
    val rng = if (seed != null) Random(seed) else Random.Default

    val generatedScenarioFullTraffics = ArrayList<GeneratedScenarioFullTraffic>(scenarioCount)

    // Readable uniqueness key: snapshot List<VehicleType?>
    val seen = HashSet<List<FullTrafficVehicleType?>>(scenarioCount * 2)

    while (generatedScenarioFullTraffics.size < scenarioCount) {
      val numberOfVehicles = rng.nextInt(maxNumOfVehicles - minNumOfVehicles + 1) + minNumOfVehicles
      val generatedScenario = sampleOne(rng, numberOfVehicles)

      val scenarioMask: List<FullTrafficVehicleType?> = generatedScenario.mask.toList()
      if (seen.add(scenarioMask)) generatedScenarioFullTraffics.add(generatedScenario)
    }

    return generatedScenarioFullTraffics
  }

  /**
   * Converts a [GeneratedScenarioFullTraffic] mask into a list of [SpawnFullTraffic] coordinates.
   *
   * @param scenario Scenario to convert.
   * @return List of placed vehicles with lane/area/index coordinates.
   */
  fun toCoordinates(scenario: GeneratedScenarioFullTraffic): List<SpawnFullTraffic> {
    val spawnFullTrafficAreas = ArrayList<SpawnFullTraffic>(scenario.vehiclesCount())
    for (index in scenario.mask.indices) {
      val placedVehicle = scenario.mask[index] ?: continue
      val lane = index / scenario.numberOfBlocksPerLane
      val area = index % scenario.numberOfBlocksPerLane
      spawnFullTrafficAreas.add(
          SpawnFullTraffic(type = placedVehicle, lane = lane, area = area, index = index))
    }
    return spawnFullTrafficAreas
  }

  /**
   * Renders a [GeneratedScenarioFullTraffic] as a TikZ picture (LaTeX) showing the road grid and
   * vehicle placements.
   *
   * The result is a standalone `tikzpicture` environment that can be embedded into LaTeX.
   *
   * @param scenario Scenario to render.
   * @param laneHeightCm Height per lane in centimeters (TikZ y-unit).
   * @param showLabels Whether to show distance labels ("0 km" ... "10 km").
   * @param tikzFillByType TikZ style snippets per vehicle type (e.g., `fill=red!70,opacity=0.85`).
   * @return TikZ code as a string.
   */
  fun toTikz(
      scenario: GeneratedScenarioFullTraffic,
      laneHeightCm: Double = 0.8,
      showLabels: Boolean = true,
      tikzFillByType: Map<FullTrafficVehicleType, String> =
          mapOf(
              FullTrafficVehicleType.TRUCK to "fill=orange!75,opacity=0.85",
              FullTrafficVehicleType.CAR_CALM to "fill=blue!60,opacity=0.80",
              FullTrafficVehicleType.CAR_NORMAL to "fill=green!60,opacity=0.80",
              FullTrafficVehicleType.CAR_SPORTY to "fill=red!70,opacity=0.85",
          )
  ): String {

    /**
     * Creates the `\foreach \x/\y in {...}` list for a specific vehicle [type].
     *
     * Each item is encoded as `area/lane`, matching the TikZ loop variable names (`\x/\y`).
     */
    fun listForType(type: FullTrafficVehicleType): String {
      val items = mutableListOf<String>()
      for (index in scenario.mask.indices) {
        if (scenario.mask[index] == type) {
          val lane = index / scenario.numberOfBlocksPerLane
          val area = index % scenario.numberOfBlocksPerLane
          items.add("${area}/${lane}") // (x,y)=(block,lane)
        }
      }
      return items.joinToString(",")
    }

    val overlays =
        buildString {
              for (vehicleType in FullTrafficVehicleType.entries) {
                val list = listForType(vehicleType)
                if (list.isBlank()) continue
                val style = tikzFillByType[vehicleType] ?: "fill=black!60,opacity=0.7"
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
        \def\nLanes{${scenario.numberOfLanes}}
        \def\nBlocks{${scenario.numberOfBlocksPerLane}}

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

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as FullTrafficScenarioGenerator

    if (scenarioCount != other.scenarioCount) return false
    if (minNumOfVehicles != other.minNumOfVehicles) return false
    if (maxNumOfVehicles != other.maxNumOfVehicles) return false
    if (numberOfLanes != other.numberOfLanes) return false
    if (numberOfBlocksPerLane != other.numberOfBlocksPerLane) return false
    if (seed != other.seed) return false
    if (fullTrafficVehicleTypes != other.fullTrafficVehicleTypes) return false
    if (!distributionOfVehicleTypes.contentEquals(other.distributionOfVehicleTypes)) return false
    if (probabilityOfLaneByFullTrafficVehicleType !=
        other.probabilityOfLaneByFullTrafficVehicleType)
        return false

    return true
  }

  override fun hashCode(): Int {
    var result = scenarioCount
    result = 31 * result + minNumOfVehicles
    result = 31 * result + maxNumOfVehicles
    result = 31 * result + numberOfLanes
    result = 31 * result + numberOfBlocksPerLane
    result = 31 * result + (seed ?: 0)
    result = 31 * result + fullTrafficVehicleTypes.hashCode()
    result = 31 * result + distributionOfVehicleTypes.contentHashCode()
    result = 31 * result + probabilityOfLaneByFullTrafficVehicleType.hashCode()
    return result
  }
}
