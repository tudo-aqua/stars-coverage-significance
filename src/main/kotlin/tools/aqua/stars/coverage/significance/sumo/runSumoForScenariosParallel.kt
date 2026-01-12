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
import kotlin.io.path.absolutePathString
import tools.aqua.stars.coverage.significance.COLLISION_DIR
import tools.aqua.stars.coverage.significance.ConsoleProgress
import tools.aqua.stars.coverage.significance.EXPORT_DIR
import tools.aqua.stars.coverage.significance.GRID_TRAFFIC_DIR

/**
 * Function to run SUMO for a list of scenario files.
 *
 * @param scenarioFiles List of scenario files to simulate.
 * @param baseDir Base directory where the net file and vTypes file are located.
 * @param sumoBinary Path to the SUMO binary.
 * @param netFileName Name of the network file.
 * @param vTypesFileName Name of the vehicle types file.
 * @param parallelism Number of parallel threads to use.
 * @param requireRouExtension If true, only files with ".rou.xml" extension are considered.
 * @param failFast If true, the function will throw an exception on the first simulation failure
 */
fun runSumoForScenariosParallel(
    scenarioFiles: List<File>,
    baseDir: Path = Path(GRID_TRAFFIC_DIR),
    sumoBinary: String = "sumo",
    netFileName: String = "grid_highway.net.xml",
    vTypesFileName: String = "vTypes.add.xml",
    parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    requireRouExtension: Boolean = true,
    failFast: Boolean = true,
): List<SumoRunResult> {
  val netFile = baseDir.resolve(netFileName)
  val vTypesFile = baseDir.resolve(vTypesFileName)
  val exportDir = Path(EXPORT_DIR)
  val collisionDir = Path(COLLISION_DIR)

  require(Files.exists(netFile)) { "Missing net file: $netFile" }
  require(Files.exists(vTypesFile)) { "Missing vTypes file: $vTypesFile" }
  Files.createDirectories(exportDir)
  Files.createDirectories(collisionDir)

  val inputs =
      scenarioFiles
          .asSequence()
          .filter { it.isFile }
          .filter { !requireRouExtension || it.name.endsWith(".rou.xml") }
          .toList()

  if (inputs.isEmpty()) return emptyList()

  println("Simulate ${inputs.size} scenarios with SUMO using $parallelism threads...")
  val pb = ConsoleProgress(inputs.size, label = "SUMO", barWidth = 30)
  pb.render(0, "starting")

  fun scenarioIdOf(f: File): String = f.name.removeSuffix(".rou.xml")

  fun buildCmd(routeFile: Path, scenarioId: String): List<String> {
    val netstateOut = exportDir.resolve("$scenarioId.export.xml")
    val collisionOut = collisionDir.resolve("$scenarioId.collisions.xml")

    return buildList {
      add(sumoBinary)
      add("--insertion-checks")
      add("none")
      add("--route-steps")
      add("0")
      add("--step-length")
      add("0.1")
      add("--net-file")
      add(netFile.absolutePathString())
      add("--additional-files")
      add(vTypesFile.absolutePathString())
      add("--route-files")
      add(routeFile.absolutePathString())
      add("--netstate-dump")
      add(netstateOut.absolutePathString())
      add("--collision-output")
      add(collisionOut.absolutePathString())
    }
  }

  val executor = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
  val cs = ExecutorCompletionService<SumoRunResult>(executor)

  try {
    // Submit all tasks
    for (f in inputs) {
      cs.submit {
        val sid = scenarioIdOf(f)
        val cmd = buildCmd(f.toPath(), sid)

        val proc = ProcessBuilder(cmd).directory(baseDir.toFile()).redirectErrorStream(true).start()

        val stdout = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()

        SumoRunResult(
            scenarioId = sid, exitCode = code, stdout = stdout, scenarioFilePath = f.toPath())
      }
    }

    // Collect results as they finish (and update progress)
    val results = ArrayList<SumoRunResult>(inputs.size)
    var completed = 0

    while (completed < inputs.size) {
      val res = cs.take().get()
      completed++

      if (res.exitCode != 0) {
        // Print failure context
        println()
        println("SUMO failed for scenarioId=${res.scenarioId} (exit=${res.exitCode})")
        println("Output:\n${res.stdout}")
        println("Command:")
        println(buildCmd(res.scenarioFilePath, res.scenarioId).joinToString(" "))

        if (failFast) error("SUMO simulation failed for scenarioId=${res.scenarioId}")
      }

      results.add(res)
      pb.render(completed, res.scenarioId)
    }

    return results
  } finally {
    executor.shutdownNow()
  }
}
