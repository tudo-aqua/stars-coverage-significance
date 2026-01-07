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

/**
 * A generated placement scenario represented by a 1D mask.
 *
 * The mask contains either:
 * - `null` (no vehicle)
 * - a [FullTrafficVehicleType] (a vehicle of that type placed at this cell)
 *
 * @property mask Placement mask of size `nL * nP` (indexing: `idx = lane * nP + area`).
 * @property numberOfLanes Number of lanes.
 * @property numberOfBlocksPerLane Number of longitudinal areas (blocks) per lane.
 */
data class GeneratedScenarioFullTraffic(
    val mask: Array<FullTrafficVehicleType?>,
    val numberOfLanes: Int,
    val numberOfBlocksPerLane: Int
) {
  /**
   * Total number of discrete cells in the scenario (`numberOfLanes * numberOfBlocksPerLane`).
   *
   * @return Total number of cells.
   */
  fun getBlockCount(): Int = numberOfLanes * numberOfBlocksPerLane

  /**
   * Counts the number of placed vehicles.
   *
   * @return The number of non-null entries in [mask].
   */
  fun vehiclesCount(): Int = mask.count { it != null }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as GeneratedScenarioFullTraffic

    if (numberOfLanes != other.numberOfLanes) return false
    if (numberOfBlocksPerLane != other.numberOfBlocksPerLane) return false
    if (!mask.contentEquals(other.mask)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = numberOfLanes
    result = 31 * result + numberOfBlocksPerLane
    result = 31 * result + mask.contentHashCode()
    return result
  }
}
