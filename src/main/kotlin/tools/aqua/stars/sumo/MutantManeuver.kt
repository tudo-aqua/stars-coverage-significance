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

package tools.aqua.stars.sumo

/**
 * The result produced by one control tick of a [Mutant].
 *
 * @property newSpeedMps The speed command sent to the ego vehicle in m/s.
 * @property laneChangeDirection The direction of a lane-change request issued this tick.
 */
data class MutantManeuver(
    val newSpeedMps: Double,
    val laneChangeDirection: LaneChangeDirection,
)

/**
 * Represents possible directions for a lane change in a traffic simulation context.
 *
 * This enumeration defines the following states:
 * - `NO_LANE_CHANGE`: Indicates that no lane change is to be performed.
 * - `CHANGE_LEFT`: Indicates a lane change to the left.
 * - `CHANGE_RIGHT`: Indicates a lane change to the right.
 */
enum class LaneChangeDirection {
  NO_LANE_CHANGE,
  CHANGE_LEFT,
  CHANGE_RIGHT;

  /** Holds static methods for [LaneChangeDirection] enum values. */
  companion object {
    /** Converts a direction integer to a [LaneChangeDirection] enum value. */
    fun fromDirection(dir: Int?): LaneChangeDirection {
      return when (dir) {
        1 -> CHANGE_LEFT
        -1 -> CHANGE_RIGHT
        else -> NO_LANE_CHANGE
      }
    }
  }
}
