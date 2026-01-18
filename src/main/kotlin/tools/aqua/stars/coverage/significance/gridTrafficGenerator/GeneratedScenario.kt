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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import tools.aqua.stars.coverage.significance.SCENARIO_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationVehicleState

@SuppressWarnings("StringLiteralDuplication")
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

  /** Human-readable scenario identifier string. */
  val id: String by lazy { buildHumanReadableId() }

  /**
   * Builds a human-readable scenario identifier string.
   *
   * @param posResolution Resolution for quantizing positions (factor, digits)
   * @param includeOptionalHashSuffix If true, appends a tiny hash suffix to reduce
   */
  fun buildHumanReadableId(
      posResolution: Pair<Double, Int> = 10.0 to 4,
      includeOptionalHashSuffix: Boolean = false
  ): String {
    fun typeLetter(t: VehicleType): Char {
      val sid = t.sumoId.lowercase(Locale.ROOT)
      val nm = t.name.lowercase(Locale.ROOT)
      return when {
        sid == "ego" || nm == "ego" -> 'e'
        "calm" in sid || "calm" in nm -> 'c'
        "normal" in sid || "normal" in nm -> 'n'
        "speedy" in sid || "speedy" in nm -> 's'
        else -> t.name.first().lowercaseChar()
      }
    }

    fun quantizePos(pMeters: Double): Int =
        (pMeters * posResolution.first).roundToInt().coerceAtLeast(0)

    fun pad(num: Int): String = num.toString().padStart(posResolution.second, '0')

    val parts =
        placements
            .sortedWith(
                compareBy<Spawn>(
                    { it.row }, { it.lane }, { it.type.sumoId }, { it.positionMeters }))
            .map { s ->
              val posQ = quantizePos(s.positionMeters)
              "r${s.row}l${s.lane}${typeLetter(s.type)}${pad(posQ)}"
            }

    val base = parts.joinToString(separator = "__")

    // Ensure filename-safe charset: [a-z0-9_]
    val safe = base.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]+"), "_")

    if (!includeOptionalHashSuffix) return safe

    // Optional tiny suffix: keeps id human-readable while reducing collision risk.
    val suffix = safe.hashCode().toUInt().toString(16)
    return "${safe}__h$suffix"
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

  /**
   * Builds a SUMO *.rou.xml for this scenario.
   *
   * This file references vTypes via vehicle@type="<id>" and does NOT define vTypes itself. Load
   * vTypes once via --additional-files (recommended) or by listing a types file first in
   * --route-files.
   */
  fun toRouXml(cfg: SumoRouExportConfig = SumoRouExportConfig()): String {
    require(cfg.routeEdges.isNotEmpty()) { "routeEdges must not be empty" }

    fun esc(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun fmtTime(t: Double): String = String.format(Locale.US, "%.2f", t)
    fun fmtPos(p: Double): String = String.format(Locale.US, "%.2f", p)

    val edgesAttr = esc(cfg.routeEdges.joinToString(" "))

    val sorted =
        placements.sortedWith(compareBy<Spawn>({ it.row }, { it.lane }, { it.positionMeters }))

    return buildString {
      appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
      appendLine("<routes>")
      appendLine("""  <route id="${esc(id)}" edges="$edgesAttr"/>""")

      for ((k, sp) in sorted.withIndex()) {
        val vehId = "${cfg.vehicleIdPrefix}_${sp.type}_${esc(id)}_r${sp.row}_l${sp.lane}_$k"
        val typeId = sp.type.sumoId // e.g., "ego", "car_calm", "car_normal", "car_speedy"
        val departLane = sp.lane

        appendLine(
            //            """  <vehicle id="$vehId" type="${esc(typeId)}" route="${esc(id)}"
            // depart="begin" departLane="$departLane" departPos="${fmtPos(sp.positionMeters)}"
            // departSpeed="${esc(sp.type.departSpeedMs.toString())}"/>""")
            """  <vehicle id="$vehId" type="${esc(typeId)}" route="${esc(id)}" depart="${fmtTime(cfg.departTimeSeconds)}" departLane="$departLane" departPos="${fmtPos(sp.positionMeters)}" departSpeed="${esc(sp.type.departSpeedMs.toString())}"/>""")
      }

      appendLine("</routes>")
    }
  }

  /** Convenience: write the per-scenario file to disk. */
  fun writeRouXml(outFile: Path, cfg: SumoRouExportConfig = SumoRouExportConfig()) {
    Files.createDirectories(outFile.parent)
    Files.writeString(outFile, toRouXml(cfg), StandardCharsets.UTF_8)
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

  /**
   * Returns the index in the occupancy array for the given row and lane.
   *
   * @param row Row index (0..2).
   * @param lane Lane index (0..2).
   * @return Index in the occupancy array.
   */
  fun idx(row: Int, lane: Int): Int = row * 3 + lane

  /**
   * Maps a [VehicleType] to a [ScenarioStartingConfigurationVehicleState].
   *
   * @param t Vehicle type.
   * @return Corresponding vehicle state.
   */
  fun vehicleTypeToState(t: VehicleType): ScenarioStartingConfigurationVehicleState =
      when (t) {
        VehicleType.EGO -> ScenarioStartingConfigurationVehicleState.EGO
        VehicleType.CAR_NORMAL -> ScenarioStartingConfigurationVehicleState.SAME_SPEED
        VehicleType.CAR_SPEEDY -> ScenarioStartingConfigurationVehicleState.FASTER
        VehicleType.CAR_CALM -> ScenarioStartingConfigurationVehicleState.SLOWER
      }

  /**
   * Returns the [ScenarioStartingConfigurationVehicleState] for the given cell.
   *
   * @param row Row index (0..2).
   * @param lane Lane index (0..2).
   * @return Vehicle state in the cell.
   */
  fun cellState(row: Int, lane: Int): ScenarioStartingConfigurationVehicleState {
    // Ego is not stored in occupancy; it is represented in placements and excluded from M.
    if (row == 1 && lane == egoLane) return ScenarioStartingConfigurationVehicleState.EGO
    val t = occupancy[idx(row, lane)] ?: return ScenarioStartingConfigurationVehicleState.NONE
    return vehicleTypeToState(t)
  }

  /**
   * Converts this [GeneratedScenario] to a [ScenarioStartingConfigurationEntry] for database
   * storage.
   *
   * @return [ScenarioStartingConfigurationEntry] for this scenario.
   */
  fun toScenarioStartingConfigurationEntry(): ScenarioStartingConfigurationEntry {
    val scenarioFileName = "${id}.$SCENARIO_FILE_EXTENSION"

    return ScenarioStartingConfigurationEntry(
        id = null,
        hash = buildHumanReadableId(),
        topLeft = cellState(row = 2, lane = 2),
        topCenter = cellState(row = 2, lane = 1),
        topRight = cellState(row = 2, lane = 0),
        middleLeft = cellState(row = 1, lane = 2),
        middleCenter = cellState(row = 1, lane = 1),
        middleRight = cellState(row = 1, lane = 0),
        bottomLeft = cellState(row = 0, lane = 2),
        bottomCenter = cellState(row = 0, lane = 1),
        bottomRight = cellState(row = 0, lane = 0),
        scenarioFileName = scenarioFileName)
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
