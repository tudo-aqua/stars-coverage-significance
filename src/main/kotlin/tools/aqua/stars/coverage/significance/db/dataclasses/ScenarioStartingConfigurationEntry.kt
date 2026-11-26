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

import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CELL_COUNT
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GRID_SIZE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn

/**
 * Data class representing a row in the [ScenarioStartingConfigurationTable].
 *
 * @property id The unique identifier of the scenario, or `null` before insertion.
 * @property sequenceNumber The insertion order of the scenario, or `null` before insertion.
 * @property humanReadableScenarioId The human-readable scenario id.
 * @property enumerationIndex Index of the scenario in the generator's enumeration, or `null` for a
 *   scenario that was not generated (e.g. one built in a test).
 * @property positionDrawIndex Position draw of the scenario's speed configuration, or `null` for a
 *   scenario that was not generated.
 * @property cells The nine cells, indexed by `row * 3 + lane`.
 */
data class ScenarioStartingConfigurationEntry(
    val id: Int? = null,
    val sequenceNumber: Long? = null,
    val humanReadableScenarioId: String,
    val enumerationIndex: Long?,
    val positionDrawIndex: Int?,
    val cells: List<ScenarioCell>,
) {
  init {
    require(cells.size == CELL_COUNT) { "Expected $CELL_COUNT cells but got ${cells.size}" }
  }

  /**
   * Converts this [ScenarioStartingConfigurationEntry] to a [GeneratedScenario].
   *
   * @return The corresponding [GeneratedScenario].
   */
  fun toGeneratedScenario(): GeneratedScenario =
      GeneratedScenario(
          spawns =
              cells.mapIndexedNotNull { index, cell ->
                cell.type?.let { type ->
                  Spawn(
                      row = index / GRID_SIZE,
                      lane = index % GRID_SIZE,
                      positionMeters = checkNotNull(cell.positionMeters),
                      type = type,
                      speedKmh = checkNotNull(cell.speedKmh),
                  )
                }
              },
          enumerationIndex = enumerationIndex,
          positionDrawIndex = positionDrawIndex,
      )
}
