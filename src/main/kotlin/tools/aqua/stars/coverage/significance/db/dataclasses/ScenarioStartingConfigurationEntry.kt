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
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationVehicleState

/**
 * Data class representing a row in the [ScenarioStartingConfigurationTable].
 *
 * @property id The unique identifier of the scenario starting configuration entry.
 * @property hash The hash of the scenario.
 * @property topLeft The [ScenarioStartingConfigurationVehicleState] in the top left corner of the
 *   grid.
 * @property topCenter The [ScenarioStartingConfigurationVehicleState] in the top center of the
 *   grid.
 * @property topRight The [ScenarioStartingConfigurationVehicleState] in the top right corner of the
 *   grid.
 * @property middleLeft The [ScenarioStartingConfigurationVehicleState] in the middle left corner of
 *   the grid.
 * @property middleCenter The [ScenarioStartingConfigurationVehicleState] in the middle center of
 *   the grid.
 * @property middleRight The [ScenarioStartingConfigurationVehicleState] in the middle right corner
 *   of the grid.
 * @property bottomLeft The [ScenarioStartingConfigurationVehicleState] in the bottom left corner of
 *   the grid.
 * @property bottomCenter The [ScenarioStartingConfigurationVehicleState] in the bottom center of
 *   the grid.
 * @property bottomRight The [ScenarioStartingConfigurationVehicleState] in the bottom right corner
 *   of the grid.
 * @property scenarioFileName The name of the scenario file.
 */
data class ScenarioStartingConfigurationEntry(
    val id: java.util.UUID? = null,
    val hash: String,
    val topLeft: ScenarioStartingConfigurationVehicleState,
    val topCenter: ScenarioStartingConfigurationVehicleState,
    val topRight: ScenarioStartingConfigurationVehicleState,
    val middleLeft: ScenarioStartingConfigurationVehicleState,
    val middleCenter: ScenarioStartingConfigurationVehicleState,
    val middleRight: ScenarioStartingConfigurationVehicleState,
    val bottomLeft: ScenarioStartingConfigurationVehicleState,
    val bottomCenter: ScenarioStartingConfigurationVehicleState,
    val bottomRight: ScenarioStartingConfigurationVehicleState,
    val scenarioFileName: String
)
