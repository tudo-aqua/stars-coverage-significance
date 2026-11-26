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

package tools.aqua.stars.coverage.significance.db.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType

/**
 * Columns of one grid cell (see
 * [tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioCell]).
 *
 * @property type Vehicle type, `null` for an empty cell.
 * @property positionMeters Front-bumper position in meters, `null` for an empty cell.
 * @property speedKmh Start speed in km/h, `null` for an empty cell.
 */
class CellColumns(
    val type: Column<GridVehicleType?>,
    val positionMeters: Column<Float?>,
    val speedKmh: Column<Int?>,
)

/**
 * Table for storing the starting scenarios.
 *
 * @property sequenceNumber Insertion order; chunk jobs split the scenarios by it.
 * @property humanReadableScenarioId Unique human-readable scenario id built from the cells.
 * @property enumerationIndex Index of the scenario in the generator's enumeration (see
 *   [tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridTrafficScenarioGenerator.scenarioAt]).
 * @property positionDrawIndex Position draw of the scenario's speed configuration, starting at 0.
 * @property cells Columns of the nine cells, indexed by `row * 3 + lane`. The database columns are
 *   named after the cell, e.g. `topLeft`, `topLeftPosition` and `topLeftSpeedKmh`.
 */
object ScenarioStartingConfigurationTable : IntIdTable("scenario_starting_configurations") {
  val sequenceNumber = long("seq").autoIncrement().uniqueIndex()
  val humanReadableScenarioId = varchar("humanReadableScenarioId", 256).uniqueIndex()
  val enumerationIndex = long("enumerationIndex").uniqueIndex()
  val positionDrawIndex = integer("positionDrawIndex")

  val cells: List<CellColumns> =
      listOf("bottom", "middle", "top").flatMap { row ->
        listOf("Right", "Center", "Left").map { lane ->
          val name = row + lane
          CellColumns(
              type = enumerationByName(name, 16, GridVehicleType::class).nullable(),
              positionMeters = float("${name}Position").nullable(),
              speedKmh = integer("${name}SpeedKmh").nullable(),
          )
        }
      }
}
