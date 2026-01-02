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
 * A directed edge in the SUMO network.
 *
 * This class stores object references (pointers) instead of ids wherever possible:
 * - [fromJunction] and [toJunction] are resolved [Junction] objects
 * - [lanes] are the lanes belonging to this edge
 *
 * @property edgeId Edge id.
 * @property fromJunction Source junction (pointer).
 * @property toJunction Target junction (pointer).
 * @property edgeFunction Edge function (e.g., "internal" or empty string).
 * @property edgePriority Priority, 0 if absent.
 * @property lanes Lanes belonging to this edge.
 */
data class Edge(
    val edgeId: String,
    val fromJunction: Junction,
    val toJunction: Junction,
    val edgeFunction: String,
    val edgePriority: Int,
    val lanes: MutableList<Lane> = mutableListOf()
) {

  /** Equality is based on [edgeId] only to avoid deep recursion via pointers. */
  override fun equals(other: Any?): Boolean = other is Edge && other.edgeId == edgeId

  /** Hash code is based on [edgeId] only. */
  override fun hashCode(): Int = edgeId.hashCode()

  /** Compact string representation to avoid recursive printing. */
  override fun toString(): String =
      "Edge(id='$edgeId', from='${fromJunction.junctionId}', to='${toJunction.junctionId}', lanes=${lanes.size})"
}
