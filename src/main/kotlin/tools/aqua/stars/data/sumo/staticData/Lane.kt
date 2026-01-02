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

package tools.aqua.stars.data.sumo.staticData

/**
 * A lane of an [Edge].
 *
 * This class stores a pointer to its owning [parentEdge].
 *
 * @property laneId Lane id (globally unique).
 * @property laneIndex Index within edge.
 * @property speedLimitMetersPerSecond Speed limit (m/s).
 * @property laneLengthMeters Lane length (m).
 * @property laneShape Lane shape polyline.
 * @property parentEdge The edge this lane belongs to (pointer).
 */
data class Lane(
    val laneId: String,
    val laneIndex: Int,
    val speedLimitMetersPerSecond: Float,
    val laneLengthMeters: Float,
    val laneShape: List<Point>,
    val parentEdge: Edge
) {

  /** Equality is based on [laneId] only. */
  override fun equals(other: Any?): Boolean = other is Lane && other.laneId == laneId

  /** Hash code is based on [laneId] only. */
  override fun hashCode(): Int = laneId.hashCode()

  /** Compact string representation to avoid recursive printing. */
  override fun toString(): String =
      "Lane(id='$laneId', index=$laneIndex, edge='${parentEdge.edgeId}')"
}
