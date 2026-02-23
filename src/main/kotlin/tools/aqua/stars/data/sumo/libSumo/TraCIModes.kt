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

package tools.aqua.stars.data.sumo.libSumo

import java.util.ArrayList

/**
 * Per-vehicle TraCI/libsumo mode configuration.
 *
 * @property speedMode Speed mode.
 * @property laneChangeMode Lane change mode.
 */
data class TraCIModes(val speedMode: Int, val laneChangeMode: Int) {
  override fun toString(): String {
    fun bit(v: Int, b: Int) = ((v ushr b) and 1) == 1
    fun bits2(v: Int, lo: Int) = (v ushr lo) and 0b11

    fun yn(x: Boolean) = if (x) "ON" else "OFF"

    // ---- speedMode (0xb3) ----
    // bit0: Regard safe speed
    // bit1: Regard maximum acceleration
    // bit2: Regard maximum deceleration
    // bit3: Regard right of way at intersections (approaching foes outside intersection)
    // bit4: Brake hard to avoid passing a red light
    // bit5: Disregard right of way within intersections (foes entered intersection)
    // bit6: Disregard speed limit
    //
    // Docs note: "Setting the bit enables the check (the according value is regarded)"
    // For bit5/bit6, the doc label is "Disregard ...", so ON means "disregard".
    val speedLines =
        listOf(
            "bit0 Regard safe speed = ${yn(bit(speedMode, 0))}",
            "bit1 Regard maximum acceleration = ${yn(bit(speedMode, 1))}",
            "bit2 Regard maximum deceleration = ${yn(bit(speedMode, 2))}",
            //      "bit3 Regard right of way at intersections (approaching foes outside
            // intersection) = ${yn(bit(speedMode, 3))}",
            //      "bit4 Brake hard to avoid passing a red light = ${yn(bit(speedMode, 4))}",
            //      "bit5 Disregard right of way within intersections (foes entered intersection) =
            // ${yn(bit(speedMode, 5))}",
            "bit6 Disregard speed limit = ${yn(bit(speedMode, 6))}")

    fun lc2BitsDesc(field: Int, label: String): String {
      val desc =
          when (field) {
            0b00 -> "do no $label changes"
            0b01 -> "do $label changes if not in conflict with a TraCI request"
            0b10 -> "do $label change even if overriding TraCI request"
            0b11 -> "RESERVED/UNSPECIFIED (11)"
            else -> "?"
          }
      return desc
    }

    // ---- laneChangeMode (0xb6) ----
    //    val strategic = bits2(laneChangeMode, 0)   // bit1..0
    //    val cooperative = bits2(laneChangeMode, 2) // bit3..2
    val speedGain = bits2(laneChangeMode, 4) // bit5..4
    val rightDrive = bits2(laneChangeMode, 6) // bit7..6
    val followTraCI = bits2(laneChangeMode, 8) // bit9..8
    //    val sublane = bits2(laneChangeMode, 10)    // bit11..10

    val followTraCIDesc =
        when (followTraCI) {
          0b00 ->
              "do not respect other drivers when following TraCI requests, adapt speed to fulfill request"
          0b01 ->
              "avoid immediate collisions when following a TraCI request, adapt speed to fulfill request"
          0b10 ->
              "respect the speed / brake gaps of others when changing lanes, adapt speed to fulfill request"
          0b11 -> "respect the speed / brake gaps of others when changing lanes, no speed adaption"
          else -> "?"
        }

    val laneLines =
        listOf(
            //      "bit1..0  strategic: ${lc2BitsDesc(strategic, "strategic")}",
            //      "bit3..2  cooperative: ${lc2BitsDesc(cooperative, "cooperative")}",
            "bit5..4  speed gain: ${lc2BitsDesc(speedGain, "speed gain")}",
            "bit7..6  right drive: ${lc2BitsDesc(rightDrive, "right drive")}",
            "bit9..8  TraCI request following: $followTraCIDesc",
            //      "bit11..10 sublane: ${lc2BitsDesc(sublane, "sublane")}"
        )

    fun bin(v: Int, width: Int): String = v.toString(2).padStart(width, '0')

    return buildString {
      appendLine("TraCIModes(")
      appendLine("  speedMode=$speedMode (bin=${bin(speedMode, 7)}; bits 6..0)")
      speedLines.forEach { appendLine("    $it") }
      appendLine("  laneChangeMode=$laneChangeMode (bin=${bin(laneChangeMode, 12)}; bits 11..0)")
      laneLines.forEach { appendLine("    $it") }
      append(")")
    }
  }

  /** Holds static utility methods. */
  companion object {

    /** All speedMode variants that only toggle bits 3,4,5. All other bits are 0. */
    val allSpeedModes: List<Int> = allBitsetValues(intArrayOf(3, 4, 5))

    /** All laneChangeMode variants that only toggle bits 4,5,6,7,8,9. All other bits are 0. */
    val allLaneChangeModes: List<Int> = allBitsetValues(intArrayOf(4, 5, 6, 7, 8, 9))

    /** Cartesian product of all requested combinations. */
    val allModeCombinations: List<TraCIModes> =
        allSpeedModes.flatMap { sm -> allLaneChangeModes.map { lcm -> TraCIModes(sm, lcm) } }

    /**
     * Build all bitset values for the given bit positions.
     *
     * Example: bits [3,4,5] -> returns 0..(2^3-1) mapped onto those positions.
     */
    private fun allBitsetValues(bits: IntArray): List<Int> {
      require(bits.isNotEmpty())
      val n = bits.size
      val result = ArrayList<Int>(1 shl n)
      for (mask in 0 until (1 shl n)) {
        var value = 0
        for (i in 0 until n) {
          if (((mask shr i) and 1) == 1) {
            value = value or (1 shl bits[i])
          }
        }
        result += value
      }
      return result
    }
  }
}
