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
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.hooks.defaulthooks.MinTicksPerTickSequenceHook
import tools.aqua.stars.core.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.core.metrics.evaluation.TickCountMetric
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.generateGridTrafficScenarios
import tools.aqua.stars.coverage.significance.metrics.FirstTSCInstanceChangeMetric
import tools.aqua.stars.coverage.significance.metrics.StartingValidTSCInstancesPerTSCMetric
import tools.aqua.stars.coverage.significance.sumo.cleanGenerationFiles
import tools.aqua.stars.coverage.significance.sumo.runSumoForScenariosParallel
import tools.aqua.stars.data.sumo.SumoImporter

/** Directory paths for grid traffic scenarios. */
const val GRID_TRAFFIC_DIR = "sumo_data/gridTrafficScenarios"
/** Sub-directory for scenario files. */
const val SCENARIO_DIR = "$GRID_TRAFFIC_DIR/scenarios"
/** Sub-directory for exported SUMO files. */
const val EXPORT_DIR = "$GRID_TRAFFIC_DIR/export"
/** Sub-directory for collision files. */
const val COLLISION_DIR = "$GRID_TRAFFIC_DIR/collision"
/** File extension for scenario files. */
const val SCENARIO_FILE_EXTENSION = "rou.xml"
/** File extension for exported SUMO files. */
const val EXPORT_FILE_EXTENSION = "export.xml"
/** File extension for collision files. */
const val COLLISION_FILE_EXTENSION = "collisions.xml"

/** Generation of scenarios and printing of the TikZ code for the first scenario. */
fun main() {
  cleanGenerationFiles()

  generateGridTrafficScenarios(n = 1, seed = 2)

  val scenarioFiles = Path(SCENARIO_DIR).toFile().listFiles()?.toList()?.sorted() ?: emptyList()

  runSumoForScenariosParallel(scenarioFiles = scenarioFiles, parallelism = 12, writeCfgFiles = true)

  val exportFiles = Path(EXPORT_DIR).toFile().listFiles()?.toList()?.sorted() ?: emptyList()
  val collisionFiles = Path(COLLISION_DIR).toFile().listFiles()?.toList()?.sorted() ?: emptyList()

  val bufferSizeInSeconds = 10.0
  val takeOnlyTicksAtXMillis = 100
  val bufferSize = ((bufferSizeInSeconds * 1000) / takeOnlyTicksAtXMillis).toInt()

  val importer = SumoImporter()
  val tickSequence =
      importer.loadTicks(
          scenarioFiles = scenarioFiles,
          exportFiles = exportFiles,
          collisionsFiles = collisionFiles,
          bufferSize = bufferSize,
          netFilePath = Path("$GRID_TRAFFIC_DIR/grid_highway.net.xml"),
          vehicleTypesAdditionalFilePath = Path("$GRID_TRAFFIC_DIR/vTypes.add.xml"),
          takeOnlyTicksAtXMillis = takeOnlyTicksAtXMillis)

  val staticTsc = staticTsc()
  val tscEvaluation = TSCEvaluation(staticTsc, writePlots = true)
  tscEvaluation.registerPreTickEvaluationHooks(MinTicksPerTickSequenceHook(bufferSize))
  tscEvaluation.registerMetricProviders(
      InvalidTSCInstancesPerTSCMetric(),
      StartingValidTSCInstancesPerTSCMetric(),
      TickCountMetric(),
      FirstTSCInstanceChangeMetric())
  tscEvaluation.registerDefaultHooks()
  tscEvaluation.runEvaluation(tickSequence)
}
