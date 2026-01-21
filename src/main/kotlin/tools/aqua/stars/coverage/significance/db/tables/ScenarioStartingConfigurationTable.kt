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

import org.jetbrains.exposed.dao.id.UUIDTable

/**
 * Table for storing scenario starting configurations.
 *
 * @property sequenceNumber Auto-incrementing sequence number.
 * @property humanReadableScenarioId Unique human-readable scenario ID.
 * @property topLeft Vehicle state in the top left corner of the grid.
 * @property topCenter Vehicle state in the top center of the grid.
 * @property topRight Vehicle state in the top right corner of the grid.
 * @property topLeftPosition Position of the vehicle in the top left corner of the grid.
 * @property topCenterPosition Position of the vehicle in the top center of the grid.
 * @property topRightPosition Position of the vehicle in the top right corner of the grid.
 * @property middleLeft Vehicle state in the middle left corner of the grid.
 * @property middleCenter Vehicle state in the middle center of the grid.
 * @property middleRight Vehicle state in the middle right corner of the grid.
 * @property middleLeftPosition Position of the vehicle in the middle left corner of the grid.
 * @property middleCenterPosition Position of the vehicle in the middle center of the grid.
 * @property middleRightPosition Position of the vehicle in the middle right corner of the grid.
 * @property bottomLeft Vehicle state in the bottom left corner of the grid.
 * @property bottomCenter Vehicle state in the bottom center of the grid.
 * @property bottomRight Vehicle state in the bottom right corner of the grid.
 * @property bottomLeftPosition Position of the vehicle in the bottom left corner of the grid.
 * @property bottomCenterPosition Position of the vehicle in the bottom center of the grid.
 * @property bottomRightPosition Position of the vehicle in the bottom right corner of the grid.
 */
object ScenarioStartingConfigurationTable : UUIDTable("scenario_starting_configurations") {
  val sequenceNumber = long("seq").autoIncrement().uniqueIndex()
  val humanReadableScenarioId = varchar("humanReadableScenarioId", 256).uniqueIndex()

  val topLeft = enumerationByName("topLeft", 16, ScenarioStartingConfigurationVehicleState::class)
  val topCenter =
      enumerationByName("topCenter", 16, ScenarioStartingConfigurationVehicleState::class)
  val topRight = enumerationByName("topRight", 16, ScenarioStartingConfigurationVehicleState::class)

  val topCenterPosition = float("topCenterPosition").nullable()
  val topLeftPosition = float("topLeftPosition").nullable()
  val topRightPosition = float("topRightPosition").nullable()

  val middleLeft =
      enumerationByName("middleLeft", 16, ScenarioStartingConfigurationVehicleState::class)
  val middleCenter =
      enumerationByName("middleCenter", 16, ScenarioStartingConfigurationVehicleState::class)
  val middleRight =
      enumerationByName("middleRight", 16, ScenarioStartingConfigurationVehicleState::class)

  val middleCenterPosition = float("middleCenterPosition").nullable()
  val middleLeftPosition = float("middleLeftPosition").nullable()
  val middleRightPosition = float("middleRightPosition").nullable()

  val bottomLeft =
      enumerationByName("bottomLeft", 16, ScenarioStartingConfigurationVehicleState::class)
  val bottomCenter =
      enumerationByName("bottomCenter", 16, ScenarioStartingConfigurationVehicleState::class)
  val bottomRight =
      enumerationByName("bottomRight", 16, ScenarioStartingConfigurationVehicleState::class)

  val bottomCenterPosition = float("bottomCenterPosition").nullable()
  val bottomLeftPosition = float("bottomLeftPosition").nullable()
  val bottomRightPosition = float("bottomRightPosition").nullable()

  init {
    index(false, humanReadableScenarioId)
  }
}
