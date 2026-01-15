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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import tools.aqua.stars.coverage.significance.SCENARIO_DIR

/**
 * Specification for running a SUMO scenario.
 *
 * @property sumoBinary Path to the SUMO binary.
 * @property netFile Path to the network file.
 * @property routeFile Path to the route file.
 * @property additionalFiles List of additional files to include.
 * @property insertionChecks Insertion checks setting for SUMO.
 * @property routeSteps Number of route steps.
 * @property stepLength Length of each simulation step.
 * @property netstateDump Path to the output netstate dump file.
 * @property collisionOutput Path to the output collision file.
 * @property saveCfgRelative If true, the saved configuration file will use relative paths.
 */
data class SumoScenarioRunSpecification(
    val sumoBinary: String,
    val netFile: Path,
    val routeFile: Path,
    val additionalFiles: List<Path>,
    val insertionChecks: String,
    val routeSteps: Int,
    val stepLength: Double,
    val netstateDump: Path,
    val collisionOutput: Path,
    val saveCfgRelative: Boolean = false
) {
  /**
   * Generates the explicit command line arguments for running SUMO with the specified parameters.
   */
  fun toExplicitCmd(): MutableList<String> {
    val add = additionalFiles.joinToString(",") { it.toAbsolutePath().toString() }
    return mutableListOf(
        sumoBinary,
        "--net-file",
        netFile.toAbsolutePath().toString(),
        "--route-files",
        routeFile.toAbsolutePath().toString(),
        "--additional-files",
        add,
        "--insertion-checks",
        insertionChecks,
        "--route-steps",
        routeSteps.toString(),
        "--step-length",
        stepLength.toString(),
        "--netstate-dump",
        netstateDump.toAbsolutePath().toString(),
        "--collision-output",
        collisionOutput.toAbsolutePath().toString(),
    )
  }

  /**
   * Default runtime: explicit cmd only. Test mode: same cmd + SUMO writes a .sumocfg with the exact
   * currently set values.
   */
  fun toCmd(writeCfgFile: Boolean): List<String> {
    val cmd = toExplicitCmd()
    if (writeCfgFile) {
      Files.createDirectories(Path(SCENARIO_DIR))
      cmd +=
          listOf(
              "--save-configuration",
              Path("$SCENARIO_DIR/${routeFile.fileName.toString().replace(".rou.xml", "")}")
                  .toAbsolutePath()
                  .toString()
                  .plus(".sumocfg"),
          )
      if (saveCfgRelative) cmd += "--save-configuration.relative"
    }
    return cmd
  }
}
