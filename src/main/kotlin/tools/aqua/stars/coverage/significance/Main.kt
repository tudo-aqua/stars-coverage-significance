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
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.sequences.forEach
import tools.aqua.stars.core.evaluation.TSCEvaluation
import tools.aqua.stars.core.metric.metrics.evaluation.InvalidTSCInstancesPerTSCMetric
import tools.aqua.stars.importer.carla.CarlaSimulationRunsWrapper
import tools.aqua.stars.importer.carla.loadTicks

fun main(args: Array<String>) {
  val tsc = tsc()
  val simulationRuns = getSimulationRuns("manual_tests")
  val ticks = loadTicks(simulationRuns)

  TSCEvaluation(tscList = listOf(tsc))
      .apply {
        clearHooks()
        registerDefaultHooks()
        registerMetricProviders(InvalidTSCInstancesPerTSCMetric())
      }
      .runEvaluation(ticks = ticks)
}

fun getSimulationRuns(
    simulationRunFolder: String,
    staticFilter: String = ".*",
    dynamicFilter: String = ".*"
): List<CarlaSimulationRunsWrapper> =
    File(simulationRunFolder).let { file ->
      file
          .walk()
          .filter {
            it.isDirectory && it != file && staticFilter.toRegex().containsMatchIn(it.name)
          }
          .toList()
          .mapNotNull { mapFolder ->
            var staticFile: Path? = null
            val dynamicFiles = mutableListOf<Path>()
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

            if (staticFile == null || dynamicFiles.isEmpty()) {
              return@mapNotNull null
            }

            dynamicFiles.sortBy {
              "_seed([0-9]{1,4})".toRegex().find(it.fileName.name)?.groups?.get(1)?.value?.toInt()
                  ?: 0
            }
            return@mapNotNull CarlaSimulationRunsWrapper(staticFile, dynamicFiles)
          }
    }

/**
 * Extract a zip file into any directory.
 *
 * @param zipFile src zip file
 * @param outputDir directory to extract into. There will be new folder with the zip's name inside
 *   [outputDir] directory.
 * @return the extracted directory i.e.
 */
private fun extractZipFile(zipFile: File, outputDir: File): File? {
  ZipFile(zipFile).use { zip ->
    zip.entries().asSequence().forEach { entry ->
      zip.getInputStream(entry).use { input ->
        if (entry.isDirectory) File(outputDir, entry.name).also { it.mkdirs() }
        else
            File(outputDir, entry.name)
                .also { it.parentFile.mkdirs() }
                .outputStream()
                .use { output -> input.copyTo(output) }
      }
    }
  }
  return outputDir
}
