/*
 * Copyright 2025-2026 The STARS Coverage Significance Authors
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

package tools.aqua.stars.coverage.significance

import kotlin.io.path.Path
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.generateGridTrafficScenarios
import tools.aqua.stars.coverage.significance.sumo.runSumoForScenariosParallel

/** Directory paths for grid traffic scenarios. */
const val GRID_TRAFFIC_DIR = "sumo_data/gridTrafficScenarios"
/** Sub-directory for scenario files. */
const val SCENARIO_DIR = "$GRID_TRAFFIC_DIR/scenarios"
/** Sub-directory for exported SUMO files. */
const val EXPORT_DIR = "$GRID_TRAFFIC_DIR/export"
/** Sub-directory for collision files. */
const val COLLISION_DIR = "$GRID_TRAFFIC_DIR/collision"
/** File extension for exported SUMO files. */
const val EXPORT_FILE_EXTENSION = "export.xml"
/** File extension for collision files. */
const val COLLISION_FILE_EXTENSION = "collision.xml"

/** Generation of scenarios and printing of the TikZ code for the first scenario. */
fun main() {
  generateGridTrafficScenarios()

  val scenarioFiles = Path(SCENARIO_DIR).toFile().listFiles()?.toList() ?: emptyList()

  runSumoForScenariosParallel(
      scenarioFiles = scenarioFiles,
      parallelism = 12,
  )
}
