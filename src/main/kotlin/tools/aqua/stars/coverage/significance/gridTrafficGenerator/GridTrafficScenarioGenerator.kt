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

package tools.aqua.stars.coverage.significance.gridTrafficGenerator

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM

/**
 * Exhaustive 3x3-grid scenario generator.
 *
 * @property enablePositionVariance Whether to enable position variance sampling.
 * @property positionVariantsPerOccupancy Number of position variants to sample per typed occupancy
 *   (only used if [enablePositionVariance] is `true`).
 * @property seed Optional random seed for reproducible position variance sampling.
 * @property i0Start Start of row 0 interval (meters).
 * @property i0End End of row 0 interval (meters).
 * @property i1Start Start of row 1 interval (meters).
 * @property i1End End of row 1 interval (meters).
 * @property i2Start Start of row 2 interval (meters).
 * @property i2End End of row 2 interval (meters).
 * @property minForwardGapMeters Minimum forward gap between vehicles (meters).
 */
data class GridTrafficScenarioGenerator(
    val enablePositionVariance: Boolean,
    val positionVariantsPerOccupancy: Int = 1,
    val seed: Int? = null,

    // Intervals (meters)
    val i0Start: Float = 0.0f,
    val i0End: Float = VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO,
    val i1Start: Float = i0End,
    val i1End: Float =
        i1Start +
            VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM +
            VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM,
    val i2Start: Float = i1End,
    val i2End: Float = i2Start + VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO,

    // Constraint (meters)
    val minForwardGapMeters: Float = 50.0f,
) {

  private val interval0 = Interval(i0Start, i0End)
  private val interval1 = Interval(i1Start, i1End)
  private val interval2 = Interval(i2Start, i2End)

  /** Validates the configuration parameters. */
  private fun validate() {
    require(positionVariantsPerOccupancy > 0) { "positionVariantsPerOccupancy must be > 0" }
    require(minForwardGapMeters >= 0.0) { "minForwardGapMeters must be >= 0" }
    require(interval0.end > interval0.start) { "I0 must have positive length" }
    require(interval1.end > interval1.start) { "I1 must have positive length" }
    require(interval2.end > interval2.start) { "I2 must have positive length" }

    // Ensure constructive placement is always possible for the chosen intervals and min gap.
    require(interval1.end >= interval0.start + minForwardGapMeters) {
      "I1 must allow a middle-row position that is at least d_min ahead of I0.start"
    }
    require(interval1.start <= interval2.end - minForwardGapMeters) {
      "I1 must allow a middle-row position that is at least d_min behind I2.end"
    }
    require(interval2.end >= interval0.start + minForwardGapMeters) {
      "I2.end must be at least d_min ahead of I0.start"
    }
  }

  /**
   * Generates all possible scenarios based on the configured parameters.
   *
   * @return Sequence of generated scenarios.
   */
  fun generateAll(): Sequence<GeneratedScenario> {
    validate()
    val rng = if (seed != null) Random(seed) else Random.Default

    return sequence {
      for (egoLane in 0..2) {
        val egoCellIndex = 1 * 3 + egoLane
        val otherCells = (0 until 9).filter { it != egoCellIndex }
        check(otherCells.size == 8)

        val totalTypedOccupancies = intPow(4, otherCells.size) // 4^8
        for (pattern in 0 until totalTypedOccupancies) {
          val occupancy: Array<GridVehicleType?> = arrayOfNulls(9)

          var tmp = pattern
          for (idx in otherCells.indices) {
            val digit = tmp % 4
            tmp /= 4
            occupancy[otherCells[idx]] = digitToBackgroundTypeOrNull(digit)
          }

          // Ego cell is excluded from M and therefore always null in the occupancy array.
          occupancy[egoCellIndex] = null

          val variants = if (enablePositionVariance) positionVariantsPerOccupancy else 1
          repeat(variants) { yield(buildScenarioForOccupancy(rng, egoLane, occupancy)) }
        }
      }
    }
  }

  /**
   * Builds a scenario for the given [occupancy] and [egoLane].
   *
   * @param rng Random number generator.
   * @param egoLane Lane index (0 to 2) for the ego vehicle.
   * @param occupancy Occupancy array representing vehicle types in the grid.
   * @return Generated scenario.
   */
  private fun buildScenarioForOccupancy(
      rng: Random,
      egoLane: Int,
      occupancy: Array<GridVehicleType?>,
  ): GeneratedScenario {
    val placements = mutableListOf<Spawn>()
    val egoCell = Pair(1, egoLane)

    // --- Step 1: place all middle-row vehicles first (including ego) ---
    val egoMiddlePos = placeMiddleFeasible(rng, lane = egoLane, occupancy = occupancy, isEgo = true)
    placements +=
        Spawn(row = 1, lane = egoLane, positionMeters = egoMiddlePos, type = GridVehicleType.EGO)

    for (lane in 0..2) {
      if (lane == egoLane) continue
      val t = occupancy[1 * 3 + lane]
      if (t != null) {
        val pos = placeMiddleFeasible(rng, lane = lane, occupancy = occupancy)
        placements += Spawn(row = 1, lane = lane, positionMeters = pos, type = t)
      }
    }

    // --- Step 2: place remaining rows (0, then 2), lane by lane ---
    for (row in intArrayOf(0, 2)) {
      for (lane in 0..2) {
        val spawn = placeCell(rng, row, lane, occupancy, egoCell, placements)
        if (spawn != null) placements += spawn
      }
    }

    placements.sortWith(compareBy<Spawn> { it.row }.thenBy { it.lane }.thenBy { it.positionMeters })

    val grid: Array<Array<Spawn?>> =
        Array(3) { Array<Spawn?>(3) { null } }
            .also { g ->
              for (s in placements) {
                g[s.row][s.lane] = s
              }
            }

    return GeneratedScenario(grid = grid)
  }

  /**
   * Places a middle-row vehicle in the specified [lane], ensuring feasibility given the occupancy.
   *
   * @param rng Random number generator.
   * @param lane Lane index (0 to 2).
   * @param occupancy Occupancy array representing vehicle types in the grid.
   * @param isEgo Whether the vehicle being placed is the ego vehicle (which requires special
   *   handling for position variance).
   * @return Position (meters) for the middle-row vehicle.
   */
  private fun placeMiddleFeasible(
      rng: Random,
      lane: Int,
      occupancy: Array<GridVehicleType?>,
      isEgo: Boolean = false
  ): Float {
    val row0Occupied = occupancy[0 * 3 + lane] != null
    val row2Occupied = occupancy[2 * 3 + lane] != null

    var lower = interval1.start
    var upper = interval1.end

    if (row0Occupied) lower = max(lower, interval0.start + minForwardGapMeters)
    if (row2Occupied) upper = min(upper, interval2.end - minForwardGapMeters)

    // The ego lane always has a middle-row vehicle (ego). For other lanes, this function is only
    // called if the middle-row cell is occupied in M.
    require(upper + 1e-12 >= lower) {
      "No feasible middle-row position for lane=$lane (lower=$lower, upper=$upper)"
    }

    val restricted = Interval(lower, upper)
    return if (isEgo) restricted.pick(rng, false) else restricted.pick(rng, enablePositionVariance)
  }

  /**
   * Places a vehicle in the specified [row] and [lane].
   *
   * @param rng Random number generator.
   * @param row Row index (0 or 2).
   * @param lane Lane index (0 to 2).
   * @param occupancy Occupancy array representing vehicle types in the grid.
   * @param egoCell Pair representing the ego vehicle's cell (row, lane).
   * @param placementsSoFar List of already placed vehicles.
   * @return Spawn object if placement is successful, null otherwise.
   */
  private fun placeCell(
      rng: Random,
      row: Int,
      lane: Int,
      occupancy: Array<GridVehicleType?>,
      egoCell: Pair<Int, Int>,
      placementsSoFar: List<Spawn>,
  ): Spawn? {
    require(row == 0 || row == 2) { "placeCell is only used for rows 0 and 2" }

    if (egoCell.first == row && egoCell.second == lane) return null
    val t = occupancy[row * 3 + lane] ?: return null

    val middlePos = placementsSoFar.firstOrNull { it.row == 1 && it.lane == lane }?.positionMeters
    val row0Pos = placementsSoFar.firstOrNull { it.row == 0 && it.lane == lane }?.positionMeters

    val interval: Interval =
        when (row) {
          0 -> {
            if (middlePos != null) {
              Interval(interval0.start, min(interval0.end, middlePos - minForwardGapMeters))
            } else {
              val row2Occupied = occupancy[2 * 3 + lane] != null
              if (row2Occupied) {
                // Restrict row-0 so that row-2 remains feasible later.
                Interval(interval0.start, min(interval0.end, interval2.end - minForwardGapMeters))
              } else {
                interval0
              }
            }
          }
          2 -> {
            if (middlePos != null) {
              Interval(max(interval2.start, middlePos + minForwardGapMeters), interval2.end)
            } else {
              if (row0Pos != null) {
                Interval(max(interval2.start, row0Pos + minForwardGapMeters), interval2.end)
              } else {
                interval2
              }
            }
          }
          else -> error("unreachable")
        }

    require(interval.isNonEmpty()) {
      "No feasible interval for row=$row, lane=$lane (interval=[${interval.start},${interval.end}])"
    }

    val pos = interval.pick(rng, enablePositionVariance)
    return Spawn(row = row, lane = lane, positionMeters = pos, type = t)
  }

  private fun digitToBackgroundTypeOrNull(digit: Int): GridVehicleType? =
      when (digit) {
        0 -> null
        1 -> GridVehicleType.CALM
        2 -> GridVehicleType.NORMAL
        3 -> GridVehicleType.SPEEDY
        else -> error("invalid digit $digit")
      }

  private fun intPow(base: Int, exp: Int): Int {
    var result = 1
    repeat(exp) { result *= base }
    return result
  }
}
