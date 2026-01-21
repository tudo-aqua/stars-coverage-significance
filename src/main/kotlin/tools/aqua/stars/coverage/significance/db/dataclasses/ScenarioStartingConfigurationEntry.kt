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

import java.util.UUID
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationVehicleState
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.BOTTOM_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.Spawn
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW

/**
 * Data class representing a row in the [ScenarioStartingConfigurationTable].
 *
 * @property id The unique identifier of the scenario starting configuration entry.
 * @property sequenceNumber The sequence number of the scenario starting configuration entry.
 * @property humanReadableScenarioId The human-readable scenario ID.
 * @property topLeftVehicleState The [ScenarioStartingConfigurationVehicleState] in the top left
 *   corner of the grid.
 * @property topLeftPosition The position of the vehicle in the top left corner of the grid.
 * @property topCenterVehicleState The [ScenarioStartingConfigurationVehicleState] in the top center
 *   of the grid.
 * @property topCenterPosition The position of the vehicle in the top center of the grid.
 * @property topRightVehicleState The [ScenarioStartingConfigurationVehicleState] in the top right
 *   corner of the grid.
 * @property topRightPosition The position of the vehicle in the top right corner of the grid.
 * @property middleLeftVehicleState The [ScenarioStartingConfigurationVehicleState] in the middle
 *   left corner of the grid.
 * @property middleLeftPosition The position of the vehicle in the middle left corner of the grid.
 * @property middleCenterVehicleState The [ScenarioStartingConfigurationVehicleState] in the middle
 *   center of the grid.
 * @property middleCenterPosition The position of the vehicle in the middle center of the grid.
 * @property middleRightVehicleState The [ScenarioStartingConfigurationVehicleState] in the middle
 *   right corner of the grid.
 * @property middleRightPosition The position of the vehicle in the middle right corner of the grid.
 * @property bottomLeftVehicleState The [ScenarioStartingConfigurationVehicleState] in the bottom
 *   left corner of the grid.
 * @property bottomLeftPosition The position of the vehicle in the bottom left corner of the grid
 * @property bottomCenterVehicleState The [ScenarioStartingConfigurationVehicleState] in the bottom
 *   center of the grid.
 * @property bottomCenterPosition The position of the vehicle in the bottom center of the grid.
 * @property bottomRightVehicleState The [ScenarioStartingConfigurationVehicleState] in the bottom
 *   right corner of the grid.
 * @property bottomRightPosition The position of the vehicle in the bottom right corner of the grid.
 */
data class ScenarioStartingConfigurationEntry(
    val id: UUID? = null,
    val sequenceNumber: Long? = null,
    val humanReadableScenarioId: String,
    val topLeftVehicleState: ScenarioStartingConfigurationVehicleState,
    val topLeftPosition: Float?,
    val topCenterVehicleState: ScenarioStartingConfigurationVehicleState,
    val topCenterPosition: Float?,
    val topRightVehicleState: ScenarioStartingConfigurationVehicleState,
    val topRightPosition: Float?,
    val middleLeftVehicleState: ScenarioStartingConfigurationVehicleState,
    val middleLeftPosition: Float?,
    val middleCenterVehicleState: ScenarioStartingConfigurationVehicleState,
    val middleCenterPosition: Float?,
    val middleRightVehicleState: ScenarioStartingConfigurationVehicleState,
    val middleRightPosition: Float?,
    val bottomLeftVehicleState: ScenarioStartingConfigurationVehicleState,
    val bottomLeftPosition: Float?,
    val bottomCenterVehicleState: ScenarioStartingConfigurationVehicleState,
    val bottomCenterPosition: Float?,
    val bottomRightVehicleState: ScenarioStartingConfigurationVehicleState,
    val bottomRightPosition: Float?,
) {
  fun toGeneratedScenario(): GeneratedScenario {
    val spawns: Array<Array<Spawn?>> = Array(3) { arrayOfNulls<Spawn>(3) }
    if (topLeftVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(topLeftPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(topLeftVehicleState))
      spawns[TOP_ROW][LEFT_LANE] =
          Spawn(TOP_ROW, LEFT_LANE, positionMeters = topLeftPosition, type = vehicleType)
    }
    if (topCenterVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(topCenterPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(topCenterVehicleState))
      spawns[TOP_ROW][CENTER_LANE] =
          Spawn(TOP_ROW, CENTER_LANE, positionMeters = topCenterPosition, type = vehicleType)
    }
    if (topRightVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(topRightPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(topRightVehicleState))
      spawns[TOP_ROW][RIGHT_LANE] =
          Spawn(TOP_ROW, RIGHT_LANE, positionMeters = topRightPosition, type = vehicleType)
    }
    if (middleLeftVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(middleLeftPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(middleLeftVehicleState))
      spawns[MIDDLE_ROW][LEFT_LANE] =
          Spawn(MIDDLE_ROW, LEFT_LANE, positionMeters = middleLeftPosition, type = vehicleType)
    }
    if (middleCenterVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(middleCenterPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(
                  middleCenterVehicleState))
      spawns[MIDDLE_ROW][CENTER_LANE] =
          Spawn(MIDDLE_ROW, CENTER_LANE, positionMeters = middleCenterPosition, type = vehicleType)
    }
    if (middleRightVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(middleRightPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(
                  middleRightVehicleState))
      spawns[MIDDLE_ROW][RIGHT_LANE] =
          Spawn(MIDDLE_ROW, RIGHT_LANE, positionMeters = middleRightPosition, type = vehicleType)
    }
    if (bottomLeftVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(bottomLeftPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(bottomLeftVehicleState))
      spawns[BOTTOM_ROW][LEFT_LANE] =
          Spawn(BOTTOM_ROW, LEFT_LANE, positionMeters = bottomLeftPosition, type = vehicleType)
    }
    if (bottomCenterVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(bottomCenterPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(
                  bottomCenterVehicleState))
      spawns[BOTTOM_ROW][CENTER_LANE] =
          Spawn(BOTTOM_ROW, CENTER_LANE, positionMeters = bottomCenterPosition, type = vehicleType)
    }
    if (bottomRightVehicleState != ScenarioStartingConfigurationVehicleState.NONE) {
      checkNotNull(bottomRightPosition)
      val vehicleType =
          checkNotNull(
              GridVehicleType.fromScenarioStartingConfigurationVehicleState(
                  bottomRightVehicleState))
      spawns[BOTTOM_ROW][RIGHT_LANE] =
          Spawn(BOTTOM_ROW, RIGHT_LANE, positionMeters = bottomRightPosition, type = vehicleType)
    }
    return GeneratedScenario(spawns)
  }
}
