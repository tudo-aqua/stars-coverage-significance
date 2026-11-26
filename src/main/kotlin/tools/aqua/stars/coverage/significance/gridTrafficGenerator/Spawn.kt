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

import java.util.Locale
import tools.aqua.stars.coverage.significance.MAX_SPEED_ABOVE_START_SPEED_KMH

/**
 * Coordinate form of a placed vehicle.
 *
 * @property row Row index (0..2).
 * @property lane Lane index (0..2).
 * @property positionMeters Front-bumper position in meters along the lane.
 * @property type Vehicle type.
 * @property speedKmh Start speed in km/h.
 */
data class Spawn(
    val row: Int,
    val lane: Int,
    val positionMeters: Float,
    val type: GridVehicleType,
    val speedKmh: Int,
) {
  /** Start speed in m/s. */
  val placementSpeedMps: Double
    get() = speedKmh / KMH_PER_MPS

  /**
   * The vehicle's own maximum speed in m/s, [MAX_SPEED_ABOVE_START_SPEED_KMH] above its start
   * speed, or `null` for the ego, whose speed only its controller sets.
   */
  val maxSpeedMps: Double?
    get() =
        if (type == GridVehicleType.EGO) null
        else (speedKmh + MAX_SPEED_ABOVE_START_SPEED_KMH) / KMH_PER_MPS

  /**
   * Returns a human-readable string representation of the spawn, e.g. `[2][1]N@163.4v90`: cell,
   * type code, position at 0.1 m and start speed in km/h.
   *
   * @return Human-readable string.
   */
  fun getHumanReadableString(): String =
      "[$row][$lane]${type.idCode}@${String.format(Locale.US, "%.1f", positionMeters)}v$speedKmh"

  private companion object {
    /** Conversion factor from m/s to km/h. */
    const val KMH_PER_MPS = 3.6
  }
}
