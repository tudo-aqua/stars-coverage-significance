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
import java.util.UUID
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationVehicleState
import tools.aqua.stars.coverage.significance.utils.getVehicleId

@SuppressWarnings("StringLiteralDuplication")
/** Index for the top row. */
const val TOP_ROW = 2
/** Index for the middle row. */
const val MIDDLE_ROW = 1
/** Index for the bottom row. */
const val BOTTOM_ROW = 0
/** Index for the left lane. */
const val LEFT_LANE = 2
/** Index for the center lane. */
const val CENTER_LANE = 1
/** Index for the right lane. */
const val RIGHT_LANE = 0

/**
 * Represents a generated scenario.
 *
 * @property grid 3x3 grid of spawns.
 */
data class GeneratedScenario(val grid: Array<Array<Spawn?>>) {
  init {
    require(grid.size == 3) { "Grid must have 3 rows" }
    require(grid.all { it.size == 3 }) { "Grid must have 3 lanes per row" }
  }

  /** Human-readable scenario identifier string. */
  val id: String by lazy { buildHumanReadableId() }

  /** Flattened list of all non-empty spawns . */
  val placements: List<Spawn>
    get() = buildList {
      for (r in BOTTOM_ROW..TOP_ROW) for (l in RIGHT_LANE..LEFT_LANE) grid[r][l]?.let { add(it) }
    }

  /** Returns the SUMO vehicle ID for the ego vehicle. */
  val egoId: String
    get() {
      val egoSpawn = spawnAt(MIDDLE_ROW, egoLane)
      checkNotNull(egoSpawn) { "Scenario has no EGO spawn in middle row" }
      return getVehicleId(egoSpawn.type.toString(), egoSpawn.row, egoSpawn.lane, id)
    }

  /** Lane index (0..2) where the ego vehicle is located. */
  val egoLane: Int
    get() {
      val ego =
          (RIGHT_LANE..LEFT_LANE).firstOrNull { lane ->
            grid[MIDDLE_ROW][lane]?.type == GridVehicleType.EGO
          }
      require(ego != null) { "Scenario has no EGO spawn in middle row" }
      return ego
    }

  /** Occupancy array representing the vehicle types in the 3x3 grid, excluding the ego vehicle. */
  val occupancy: Array<GridVehicleType?>
    get() {
      val occ: Array<GridVehicleType?> = arrayOfNulls(9)
      val egoCellIndex = 1 * 3 + egoLane

      for (r in BOTTOM_ROW..TOP_ROW) {
        for (l in RIGHT_LANE..LEFT_LANE) {
          val idx = r * 3 + l
          if (idx == egoCellIndex) {
            occ[idx] = null
          } else {
            occ[idx] = grid[r][l]?.type
          }
        }
      }
      return occ
    }

  /** Constructs a [GeneratedScenario] from a list of spawns. */
  constructor(spawns: List<Spawn>) : this(buildGridFromSpawns(spawns))

  /**
   * Returns the spawn at the given row and lane.
   *
   * @param row Row index (0..2).
   * @param lane Lane index (0..2).
   * @return Spawn at the given position, or null if empty.
   */
  fun spawnAt(row: Int, lane: Int): Spawn? = grid[row][lane]

  /** Builds a human-readable scenario identifier string. */
  fun buildHumanReadableId(): String {
    val parts =
        placements
            .sortedWith(
                compareBy<Spawn>(
                    { it.row }, { it.lane }, { it.type.sumoId }, { it.positionMeters }))
            .map { it.getHumanReadableString() }

    val base = parts.joinToString(separator = "__")

    return base
  }

  /**
   * Returns the count of non-empty vehicles in the grid.
   *
   * @return Number of vehicles in the grid.
   */
  fun vehiclesCount(): Int = grid.flatten().count { it != null }

  /**
   * Returns a unique occupancy key string representing the scenario's occupancy state.
   *
   * @return Occupancy key string.
   */
  fun occupancyKey(): String = buildString {
    append("egoLane=").append(egoLane).append('|')
    for (i in 0 until 9) {
      append(occupancy[i]?.name ?: "EMPTY")
      if (i != 8) append(',')
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
   * Maps a [GridVehicleType] to a [ScenarioStartingConfigurationVehicleState].
   *
   * @param t Vehicle type.
   * @return Corresponding vehicle state.
   */
  fun vehicleTypeToState(t: GridVehicleType): ScenarioStartingConfigurationVehicleState =
      when (t) {
        GridVehicleType.EGO -> ScenarioStartingConfigurationVehicleState.EGO
        GridVehicleType.NORMAL -> ScenarioStartingConfigurationVehicleState.SAME_SPEED
        GridVehicleType.SPEEDY -> ScenarioStartingConfigurationVehicleState.FASTER
        GridVehicleType.CALM -> ScenarioStartingConfigurationVehicleState.SLOWER
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
   * Returns the position (in meters) for the given cell.
   *
   * @param row Row index (0..2).
   * @param lane Lane index (0..2).
   * @return Position in meters, or null if the cell is empty.
   */
  fun position(row: Int, lane: Int): Float? =
      placements.firstOrNull { it.row == row && it.lane == lane }?.positionMeters

  /**
   * Converts this [GeneratedScenario] to a [ScenarioStartingConfigurationEntry] for database
   * storage.
   *
   * @return [ScenarioStartingConfigurationEntry] for this scenario.
   */
  fun toScenarioStartingConfigurationEntry(id: UUID? = null): ScenarioStartingConfigurationEntry =
      ScenarioStartingConfigurationEntry(
          id = id,
          sequenceNumber = null,
          humanReadableScenarioId = buildHumanReadableId(),
          topLeftVehicleState = cellState(TOP_ROW, LEFT_LANE),
          topLeftPosition = position(TOP_ROW, LEFT_LANE),
          topCenterVehicleState = cellState(TOP_ROW, CENTER_LANE),
          topCenterPosition = position(TOP_ROW, CENTER_LANE),
          topRightVehicleState = cellState(TOP_ROW, RIGHT_LANE),
          topRightPosition = position(TOP_ROW, RIGHT_LANE),
          middleLeftVehicleState = cellState(MIDDLE_ROW, LEFT_LANE),
          middleLeftPosition = position(MIDDLE_ROW, LEFT_LANE),
          middleCenterVehicleState = cellState(MIDDLE_ROW, CENTER_LANE),
          middleCenterPosition = position(MIDDLE_ROW, CENTER_LANE),
          middleRightVehicleState = cellState(MIDDLE_ROW, RIGHT_LANE),
          middleRightPosition = position(MIDDLE_ROW, RIGHT_LANE),
          bottomLeftVehicleState = cellState(BOTTOM_ROW, LEFT_LANE),
          bottomLeftPosition = position(BOTTOM_ROW, LEFT_LANE),
          bottomCenterVehicleState = cellState(BOTTOM_ROW, CENTER_LANE),
          bottomCenterPosition = position(BOTTOM_ROW, CENTER_LANE),
          bottomRightVehicleState = cellState(BOTTOM_ROW, RIGHT_LANE),
          bottomRightPosition = position(BOTTOM_ROW, RIGHT_LANE),
      )

  /**
   * Builds a SUMO *.rou.xml for this scenario.
   *
   * This file references vTypes via vehicleType="<id>" and does NOT define vTypes itself. Load
   * vTypes once via --additional-files (recommended) or by listing a types file first in
   * --route-files.
   */
  fun toRouXml(
      cfg: SumoRouExportConfig = SumoRouExportConfig(),
      changeEgoTypeTo: String? = null
  ): String {
    require(cfg.routeEdges.isNotEmpty()) { "routeEdges must not be empty" }

    fun esc(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun fmtTime(t: Double): String = String.format(Locale.US, "%.2f", t)
    fun fmtPos(p: Float): String = String.format(Locale.US, "%.2f", p)

    val edgesAttr = esc(cfg.routeEdges.joinToString(" "))

    val sorted =
        placements.sortedWith(compareBy<Spawn>({ it.row }, { it.lane }, { it.positionMeters }))

    return buildString {
      appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
      appendLine("<routes>")
      appendLine("""  <route id="${esc(id)}" edges="$edgesAttr"/>""")

      for (sp in sorted) {
        val vehId = getVehicleId(sp.type.toString(), sp.row, sp.lane, id)
        var typeId = sp.type.sumoId // e.g., "ego", "car_calm", "car_normal", "car_speedy"
        if (changeEgoTypeTo != null && typeId == "ego") {
          typeId = changeEgoTypeTo
        }
        val departLane = sp.lane

        appendLine(
            """  <vehicle id="$vehId" type="${esc(typeId)}" route="${esc(id)}" depart="${fmtTime(cfg.departTimeSeconds)}" departLane="$departLane" departPos="${fmtPos(sp.positionMeters)}" departSpeed="${esc(((sp.type.departSpeedKmh - 10) / 3.6).toString())}"/>""")
      }

      appendLine("</routes>")
    }
  }

  /**
   * Writes a SUMO *.rou.xml file for this scenario.
   *
   * This file references vTypes via vehicleType="<id>" and does NOT define vTypes itself. Load
   * vTypes once via --additional-files (recommended) or by listing a types file first in
   * --route-files.
   *
   * @param outFile Output file path.
   * @param cfg Configuration for the export.
   * @param changeEgoTypeTo If non-null, changes the type of the EGO vehicle to the given value.
   */
  fun writeRouXml(
      outFile: Path,
      cfg: SumoRouExportConfig = SumoRouExportConfig(),
      changeEgoTypeTo: String? = null
  ) {
    Files.createDirectories(outFile.parent)
    Files.writeString(outFile, toRouXml(cfg, changeEgoTypeTo), StandardCharsets.UTF_8)
  }

  /**
   * Returns an ASCII representation of the scenario for debugging purposes.
   *
   * @return ASCII string representation of the scenario.
   */
  fun toASCIIString(): String {
    val border = "+-----+-----+-----+"

    fun idx(r: Int, l: Int): Int = r * 3 + l

    fun typeToChar(t: GridVehicleType?): Char {
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
      return if (ch == ' ') "     " else "  $ch  "
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

  /** Builds a 3x3 grid from a list of spawns. */
  private companion object {
    private fun buildGridFromSpawns(spawns: List<Spawn>): Array<Array<Spawn?>> {
      val g: Array<Array<Spawn?>> = arrayOf(arrayOfNulls(3), arrayOfNulls(3), arrayOfNulls(3))

      for (sp in spawns) {
        require(sp.row in 0..2) { "Spawn row must be in 0..2 but was ${sp.row} (spawn=$sp)" }
        require(sp.lane in 0..2) { "Spawn lane must be in 0..2 but was ${sp.lane} (spawn=$sp)" }
        require(g[sp.row][sp.lane] == null) {
          "Duplicate spawn for cell [${sp.row}][${sp.lane}]: existing=${g[sp.row][sp.lane]} new=$sp"
        }
        g[sp.row][sp.lane] = sp
      }
      return g
    }
  }
}
