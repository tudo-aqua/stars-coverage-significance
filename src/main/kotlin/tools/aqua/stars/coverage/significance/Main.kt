/*
 * Copyright 2025 The STARS Coverage Significance Authors
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

import java.io.File
import java.nio.file.Path
import kotlin.io.path.name
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.metric.metrics.evaluation.*
import tools.aqua.stars.core.metric.metrics.postEvaluation.*
import tools.aqua.stars.data.av.dataclasses.*
import tools.aqua.stars.data.av.metrics.AverageVehiclesInEgosBlockMetric
import tools.aqua.stars.importer.carla.CarlaSimulationRunsWrapper
import tools.aqua.stars.importer.carla.loadSegments

fun main() {
  println("Loading simulation runs...")
  val simulationRunsWrapper = loadSingleExperiment("manual_tests/manual_recording_1")

  println("Loading segments...")
  val segments =
      loadSegments(
          simulationRunsWrappers = listOf(simulationRunsWrapper),
      )

  val validTSCInstancesPerProjectionMetric =
      ValidTSCInstancesPerTSCMetric<
          Actor, TickData, Segment, TickDataUnitSeconds, TickDataDifferenceSeconds>()

  println("Creating TSC...")
  val evaluation =
      TSCEvaluation(tscList = listOf(tsc())).apply {
        registerMetricProviders(
            TotalSegmentTickDifferencePerIdentifierMetric(),
            SegmentCountMetric(),
            AverageVehiclesInEgosBlockMetric(),
            TotalSegmentTickDifferenceMetric(),
            validTSCInstancesPerProjectionMetric,
            InvalidTSCInstancesPerTSCMetric(),
            MissedTSCInstancesPerTSCMetric(),
            MissedPredicateCombinationsPerTSCMetric(validTSCInstancesPerProjectionMetric),
            FailedMonitorsMetric(validTSCInstancesPerProjectionMetric),
        )
        println("Run Evaluation")
        runEvaluation(segments = segments)
      }
}

fun loadSingleExperiment(
    folderName: String,
    staticFilter: String = ".*",
    dynamicFilter: String = ".*"
): CarlaSimulationRunsWrapper {
  var staticFile: Path? = null
  val dynamicFiles = mutableListOf<Path>()
  val mapFolder = File(folderName)
  mapFolder.walk().forEach { mapFile ->
    if (mapFile.nameWithoutExtension.contains("static_data") &&
        staticFilter.toRegex().containsMatchIn(mapFile.name)) {
      staticFile = mapFile.toPath()
    }
    if (mapFile.nameWithoutExtension.contains("dynamic_data") &&
        dynamicFilter.toRegex().containsMatchIn(mapFile.name)) {
      dynamicFiles.add(mapFile.toPath())
    }
  }

  checkNotNull(staticFile) { "Static data file not found." }
  check(dynamicFiles.isNotEmpty()) { "Dynamic data file not found." }

  dynamicFiles.sortBy {
    "_seed([0-9]{1,4})".toRegex().find(it.fileName.name)?.groups?.get(1)?.value?.toInt() ?: 0
  }
  return CarlaSimulationRunsWrapper(staticFile, dynamicFiles)
}
