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

package tools.aqua.stars.coverage.significance.sumo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.io.path.Path
import tools.aqua.stars.coverage.significance.COLLISION_DIR
import tools.aqua.stars.coverage.significance.ConsoleProgress
import tools.aqua.stars.coverage.significance.EXPORT_DIR
import tools.aqua.stars.coverage.significance.GRID_TRAFFIC_DIR

/**
 * Function to run SUMO for a list of scenario files.
 *
 * @param scenarioFiles List of scenario files to simulate.
 * @param baseDir Base directory where the net file and vTypes file are located.
 * @param netFileName Name of the network file.
 * @param vTypesFileName Name of the vehicle types file.
 * @param parallelism Number of parallel threads to use.
 * @param requireRouExtension If true, only files with ".rou.xml" extension are considered.
 * @param failFast If true, the function will throw an exception on the first simulation failure
 *   instead of continuing. *
 * @param writeCfgFiles If true, configuration files will be written for testing purposes.
 * @return List of [SumoRunResult] containing the results of each simulation.
 */
fun runSumoForScenariosParallel(
    scenarioFiles: List<File>,
    baseDir: Path = Path(GRID_TRAFFIC_DIR),
    netFileName: String = "grid_highway.net.xml",
    vTypesFileName: String = "vTypes.add.xml",
    parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    requireRouExtension: Boolean = true,
    failFast: Boolean = true,
    writeCfgFiles: Boolean = false
): List<SumoRunResult> {
  val netFile = baseDir.resolve(netFileName)
  val vTypesFile = baseDir.resolve(vTypesFileName)
  val exportDir = Path(EXPORT_DIR)
  val collisionDir = Path(COLLISION_DIR)

  require(Files.exists(netFile)) { "Missing net file: $netFile" }
  require(Files.exists(vTypesFile)) { "Missing vTypes file: $vTypesFile" }
  Files.createDirectories(exportDir)
  Files.createDirectories(collisionDir)

  val routeFiles =
      scenarioFiles
          .asSequence()
          .filter { it.isFile }
          .filter { !requireRouExtension || it.name.endsWith(".rou.xml") }
          .toList()

  if (routeFiles.isEmpty()) return emptyList()

  println("Simulate ${routeFiles.size} scenarios with SUMO using $parallelism threads...")
  val consoleProgress = ConsoleProgress(routeFiles.size, label = "SUMO", barWidth = 30)
  consoleProgress.render(0, "starting")

  fun scenarioIdOf(f: File): String = f.name.removeSuffix(".rou.xml")

  val executor = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
  val executorCompletionService = ExecutorCompletionService<SumoRunResult>(executor)

  try {
    // Submit all tasks
    for (routeFile in routeFiles) {
      executorCompletionService.submit {
        val scenarioId = scenarioIdOf(routeFile)

        val sumoScenarioRunSpecification =
            SumoScenarioRunSpecification(
                sumoBinary = "sumo",
                netFile = netFile,
                routeFile = routeFile.toPath(),
                additionalFiles = listOf(vTypesFile),
                insertionChecks = "none",
                routeSteps = 0,
                stepLength = 0.1,
                netstateDump = exportDir.resolve("$scenarioId.export.xml"),
                collisionOutput = collisionDir.resolve("$scenarioId.collisions.xml"),
            )

        // First, optionally write the configuration file. Then run SUMO normally, as the
        // `--save-configuration` parameter is preventing SUMO from actually simulating
        if (writeCfgFiles) {
          val cmd = sumoScenarioRunSpecification.toCmd(writeCfgFile = true)
          val process =
              ProcessBuilder(cmd).directory(baseDir.toFile()).redirectErrorStream(true).start()
          process.waitFor()
        }
        val cmd = sumoScenarioRunSpecification.toCmd(writeCfgFile = false)
        val process =
            ProcessBuilder(cmd).directory(baseDir.toFile()).redirectErrorStream(true).start()

        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        SumoRunResult(
            scenarioId = scenarioId,
            exitCode = exitCode,
            stdout = stdout,
            scenarioFilePath = routeFile.toPath(),
            cmd = cmd)
      }
    }

    // Collect results as they finish (and update progress)
    val results = ArrayList<SumoRunResult>(routeFiles.size)
    var completed = 0

    while (completed < routeFiles.size) {
      val sumoRunResult = executorCompletionService.take().get()
      completed++

      if (sumoRunResult.exitCode != 0) {
        // Print failure context
        println()
        println(
            "SUMO failed for scenarioId=${sumoRunResult.scenarioId} (exit=${sumoRunResult.exitCode})")
        println("Output:\n${sumoRunResult.stdout}")
        println("Command:")
        println(sumoRunResult.cmd.joinToString(" "))

        if (failFast) error("SUMO simulation failed for scenarioId=${sumoRunResult.scenarioId}")
      }

      results.add(sumoRunResult)
      consoleProgress.render(completed, sumoRunResult.scenarioId)
    }

    return results
  } finally {
    executor.shutdownNow()
  }
}
