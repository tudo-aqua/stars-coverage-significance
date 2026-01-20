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

package tools.aqua.stars.data.sumo.xml.staticData

/**
 * A SUMO junction (node) from `.net.xml`.
 *
 * This class stores object references (pointers) instead of ids wherever possible. The importer
 * fills [incomingLanes] and [internalLanes] after all lanes are known.
 *
 * @property junctionId Junction id.
 * @property junctionType Junction type.
 * @property location The location of the junction.
 * @property shape Junction shape polyline.
 * @property incomingLanes Incoming lanes (resolved pointers).
 * @property internalLanes Internal lanes (resolved pointers).
 */
data class Junction(
    val junctionId: String,
    val junctionType: JunctionType,
    val location: Point,
    val shape: List<Point>,
    val incomingLanes: MutableList<Lane> = mutableListOf(),
    val internalLanes: MutableList<Lane> = mutableListOf()
) {

  /** Equality is based on [junctionId] only to avoid deep recursion via pointers. */
  override fun equals(other: Any?): Boolean = other is Junction && other.junctionId == junctionId

  /** Hash code is based on [junctionId] only. */
  override fun hashCode(): Int = junctionId.hashCode()

  /** Compact string representation to avoid recursive printing. */
  override fun toString(): String =
      "Junction(id='$junctionId', type='$junctionType', incoming=${incomingLanes.size}, internal=${internalLanes.size})"
}
