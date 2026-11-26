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

package tools.aqua.stars.coverage.significance.db.dataclasses

import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType

/**
 * Content of one grid cell of a stored starting scenario. A cell is either empty (all values
 * `null`) or holds one vehicle (no value `null`).
 *
 * @property type Vehicle type.
 * @property positionMeters Front-bumper position in meters along the lane.
 * @property speedKmh Start speed in km/h.
 */
data class ScenarioCell(
    val type: GridVehicleType?,
    val positionMeters: Float?,
    val speedKmh: Int?,
) {
  init {
    require((type == null) == (positionMeters == null) && (type == null) == (speedKmh == null)) {
      "A cell is either empty or has a type, a position and a speed, but was $this"
    }
  }

  /** Holds [EMPTY]. */
  companion object {
    /** An empty cell. */
    val EMPTY: ScenarioCell = ScenarioCell(type = null, positionMeters = null, speedKmh = null)
  }
}
