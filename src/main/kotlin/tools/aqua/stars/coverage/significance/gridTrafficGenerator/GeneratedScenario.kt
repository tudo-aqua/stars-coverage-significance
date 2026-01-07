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

/**
 * A generated 3x3-grid scenario.
 *
 * In the paper, a scenario is represented by its placement set $P$. This class keeps that set as
 * [placements] and additionally stores metadata that is useful in the implementation.
 *
 * @property egoLane Lane index (0..2) on which the ego vehicle is spawned.
 * @property placements Set/list of all vehicle placements (including ego) as [Spawn] tuples.
 * @property occupancy Typed occupancy over the 3x3 grid in row-major order (length 9).
 *     - `null` means the cell is empty
 *     - the ego cell (row=1, lane=egoLane) is always `null` here because it is excluded from $M$
 */
data class GeneratedScenario(
    val egoLane: Int,
    val placements: List<Spawn>,
    val occupancy: Array<VehicleType?>,
) {

  init {
    require(egoLane in 0..2) { "egoLane must be in 0..2" }
    require(occupancy.size == 9) { "occupancy must have length 9 (3x3 grid)" }
    val egoCellIndex = 1 * 3 + egoLane
    require(occupancy[egoCellIndex] == null) {
      "occupancy must be null for the ego cell; ego is represented in placements"
    }
    require(placements.any { it.type == VehicleType.EGO && it.row == 1 && it.lane == egoLane }) {
      "placements must contain the ego vehicle at row=1, lane=egoLane"
    }
  }

  /** Returns the number of vehicles in the scenario (including ego). */
  fun vehiclesCount(): Int = placements.size

  /** Returns a stable key that identifies the typed occupancy (ignoring continuous positions). */
  fun occupancyKey(): String = buildString {
    append("egoLane=").append(egoLane).append('|')
    for (i in 0 until 9) {
      append(occupancy[i]?.name ?: "EMPTY")
      if (i != 8) append(',')
    }
  }

  /** Returns an ASCII representation of the scenario for debugging purposes. */
  fun toASCIIString(): String {
    val border = "+-----+-----+-----+"

    fun idx(r: Int, l: Int): Int = r * 3 + l

    fun typeToChar(t: VehicleType?): Char {
      if (t == null) return ' '
      val sid = t.sumoId.lowercase()
      val nm = t.name.lowercase()

      return when {
        sid == "ego" || nm == "ego" -> 'E'
        "calm" in sid || "calm" in nm -> 'C'
        "normal" in sid || "normal" in nm -> 'N'
        "speedy" in sid || "speedy" in nm -> 'S'
        else -> t.name.first().uppercaseChar()
      }
    }

    fun cellChar(r: Int, l: Int): Char =
        if (r == 1 && l == egoLane) 'E' else typeToChar(occupancy[idx(r, l)])

    fun cell(r: Int, l: Int): String {
      val ch = cellChar(r, l)
      return if (ch == ' ') "     " else "  $ch  " // 5-wide inner cell like your example
    }

    fun rowLine(r: Int): String = "|" + cell(r, 0) + "|" + cell(r, 1) + "|" + cell(r, 2) + "|"

    // render rows top-down: 2 (ahead), 1 (middle), 0 (behind)
    return buildString {
      appendLine(border)
      appendLine(rowLine(2))
      appendLine(border)
      appendLine(rowLine(1))
      appendLine(border)
      appendLine(rowLine(0))
      append(border)
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as GeneratedScenario

    if (egoLane != other.egoLane) return false
    if (placements != other.placements) return false
    if (!occupancy.contentEquals(other.occupancy)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = egoLane
    result = 31 * result + placements.hashCode()
    result = 31 * result + occupancy.contentHashCode()
    return result
  }
}
