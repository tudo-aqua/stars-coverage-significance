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
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioCell
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.utils.getVehicleId
import tools.aqua.stars.coverage.significance.utils.indexedByKey

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
/** Number of rows and of lanes of the grid. */
const val GRID_SIZE = 3
/** Number of cells of the grid. */
const val CELL_COUNT = GRID_SIZE * GRID_SIZE

/**
 * Returns the index of a grid cell, `row * 3 + lane`.
 *
 * @param row Row index (0..2).
 * @param lane Lane index (0..2).
 * @return Cell index (0..8).
 */
fun cellIndex(row: Int, lane: Int): Int = row * GRID_SIZE + lane

/**
 * Represents a generated scenario.
 *
 * @property grid 3x3 grid of spawns.
 * @property enumerationIndex Index of the scenario in the generator's enumeration (see
 *   [GridTrafficScenarioGenerator.scenarioAt]), or `null` for a scenario that was not generated.
 * @property positionDrawIndex Position draw of the scenario's speed configuration, or `null` for a
 *   scenario that was not generated.
 */
data class GeneratedScenario(
    val grid: Array<Array<Spawn?>>,
    val enumerationIndex: Long? = null,
    val positionDrawIndex: Int? = null,
) {
  init {
    require(grid.size == GRID_SIZE) { "Grid must have 3 rows" }
    require(grid.all { it.size == GRID_SIZE }) { "Grid must have 3 lanes per row" }
  }

  /** Human-readable scenario identifier string. */
  val id: String by lazy { buildHumanReadableId() }

  /** Flattened list of all non-empty spawns, ordered by row and then by lane. */
  val placements: List<Spawn>
    get() = buildList {
      for (r in BOTTOM_ROW..TOP_ROW) for (l in RIGHT_LANE..LEFT_LANE) grid[r][l]?.let { add(it) }
    }

  /** Returns the SUMO vehicle ID for the ego vehicle. */
  val egoId: String
    get() {
      val egoSpawn = spawnAt(MIDDLE_ROW, egoLane)
      checkNotNull(egoSpawn) { "Scenario has no EGO spawn in middle row" }
      return getVehicleId(egoSpawn.type.idLabel, indexWithinType = 0)
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

  /**
   * Constructs a [GeneratedScenario] from a list of spawns.
   *
   * @param spawns Spawns, at most one per cell.
   * @param enumerationIndex See [GeneratedScenario.enumerationIndex].
   * @param positionDrawIndex See [GeneratedScenario.positionDrawIndex].
   */
  constructor(
      spawns: List<Spawn>,
      enumerationIndex: Long? = null,
      positionDrawIndex: Int? = null,
  ) : this(buildGridFromSpawns(spawns), enumerationIndex, positionDrawIndex)

  /**
   * Returns the spawn at the given row and lane.
   *
   * @param row Row index (0..2).
   * @param lane Lane index (0..2).
   * @return Spawn at the given position, or null if empty.
   */
  fun spawnAt(row: Int, lane: Int): Spawn? = grid[row][lane]

  /** Builds a human-readable scenario identifier string from the spawns, in row and lane order. */
  fun buildHumanReadableId(): String =
      placements.joinToString(separator = "__") { it.getHumanReadableString() }

  /**
   * Returns the count of non-empty vehicles in the grid.
   *
   * @return Number of vehicles in the grid.
   */
  fun vehiclesCount(): Int = placements.size

  /**
   * Converts this [GeneratedScenario] to a [ScenarioStartingConfigurationEntry] for database
   * storage.
   *
   * @param id Database id of the scenario, or `null` before insertion.
   * @return [ScenarioStartingConfigurationEntry] for this scenario.
   */
  fun toScenarioStartingConfigurationEntry(id: Int? = null): ScenarioStartingConfigurationEntry =
      ScenarioStartingConfigurationEntry(
          id = id,
          sequenceNumber = null,
          humanReadableScenarioId = buildHumanReadableId(),
          enumerationIndex = enumerationIndex,
          positionDrawIndex = positionDrawIndex,
          cells =
              (0 until CELL_COUNT).map { index ->
                grid[index / GRID_SIZE][index % GRID_SIZE]?.let {
                  ScenarioCell(it.type, it.positionMeters, it.speedKmh)
                } ?: ScenarioCell.EMPTY
              },
      )

  /**
   * Builds a SUMO *.rou.xml for this scenario.
   *
   * This file references vTypes via vehicleType="<id>" and does NOT define vTypes itself. Load
   * vTypes once via --additional-files (recommended) or by listing a types file first in
   * --route-files. A route file cannot set a vehicle's own maximum speed, so the vehicles keep the
   * vType's maximum speed instead of [Spawn.maxSpeedMps].
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

      for ((sp, indexWithinType) in sorted.indexedByKey { it.type.idLabel }) {
        val vehId = getVehicleId(sp.type.idLabel, indexWithinType)
        var typeId = sp.type.sumoId // e.g., "ego", "car_calm", "car_normal", "car_speedy"
        if (changeEgoTypeTo != null && typeId == "ego") {
          typeId = changeEgoTypeTo
        }
        val departLane = sp.lane

        appendLine(
            """  <vehicle id="$vehId" type="${esc(typeId)}" route="${esc(id)}" depart="${fmtTime(cfg.departTimeSeconds)}" departLane="$departLane" departPos="${fmtPos(sp.positionMeters)}" departSpeed="${esc(sp.placementSpeedMps.toString())}"/>""")
      }

      appendLine("</routes>")
    }
  }

  /**
   * Writes a SUMO *.rou.xml file for this scenario (see [toRouXml]).
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
   * Returns an ASCII representation of the scenario for debugging purposes: one character per cell
   * ([GridVehicleType.idCode]), rows from ahead (top) to behind (bottom).
   *
   * @return ASCII string representation of the scenario.
   */
  fun toASCIIString(): String {
    val border = "+-----+-----+-----+"

    fun cell(r: Int, l: Int): String = grid[r][l]?.let { "  ${it.type.idCode}  " } ?: "     "

    fun rowLine(r: Int): String = "|" + cell(r, 0) + "|" + cell(r, 1) + "|" + cell(r, 2) + "|"

    return buildString {
      appendLine(border)
      appendLine(rowLine(TOP_ROW))
      appendLine(border)
      appendLine(rowLine(MIDDLE_ROW))
      appendLine(border)
      appendLine(rowLine(BOTTOM_ROW))
      append(border)
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as GeneratedScenario

    return placements == other.placements &&
        enumerationIndex == other.enumerationIndex &&
        positionDrawIndex == other.positionDrawIndex
  }

  override fun hashCode(): Int {
    var result = placements.hashCode()
    result = 31 * result + (enumerationIndex?.hashCode() ?: 0)
    result = 31 * result + (positionDrawIndex ?: 0)
    return result
  }

  /** Builds a 3x3 grid from a list of spawns. */
  private companion object {
    private fun buildGridFromSpawns(spawns: List<Spawn>): Array<Array<Spawn?>> {
      val g: Array<Array<Spawn?>> = Array(GRID_SIZE) { arrayOfNulls(GRID_SIZE) }

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
