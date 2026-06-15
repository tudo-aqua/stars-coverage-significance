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

/** Represents the three lanes of the simulated highway, ordered right-to-left by index. */
enum class HighwayLane {
  /** The rightmost lane (lane index 0). */
  RIGHT,

  /** The middle lane (lane index 1). */
  MIDDLE,

  /** The leftmost lane (lane index 2). */
  LEFT;

  /** Holds static methods for [HighwayLane] enum values. */
  companion object {
    /** Returns the [HighwayLane] for the given SUMO lane index, or null for unknown indices. */
    fun fromLaneIndex(index: Int): HighwayLane? =
        when (index) {
          0 -> RIGHT
          1 -> MIDDLE
          2 -> LEFT
          else -> null
        }
  }
}
