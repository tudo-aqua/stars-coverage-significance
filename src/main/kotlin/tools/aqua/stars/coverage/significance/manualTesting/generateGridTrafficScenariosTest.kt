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

package tools.aqua.stars.coverage.significance.manualTesting

import kotlin.io.path.Path
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.BOTTOM_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridTrafficScenarioGenerator
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.MIDDLE_ROW
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.TOP_ROW
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

/**
 * Function to generate grid traffic scenarios.
 *
 * @param n Optional number of scenarios to generate; if null, generates all scenarios.
 * @param seed Seed for random number generation.
 * @param enablePositionVariance Whether to enable position variance sampling.
 * @param cleanGenerationFiles Whether to clean the generation files directory before generating.
 */
fun generateGridTrafficScenariosTest(
    seed: Int = 1,
    enablePositionVariance: Boolean = false,
    cleanGenerationFiles: Boolean = true
): List<GeneratedScenario> {
  val rng = Random(seed)
  val generator =
      GridTrafficScenarioGenerator(
          enablePositionVariance = enablePositionVariance,
          positionVariantsPerOccupancy = 3,
          seed = seed,
          minForwardGapMeters = 50.0f,
          i0Start = 0.0f,
          i0End = 100.0f,
          i1Start = 100.0f,
          i1End = 110.0f,
          i2Start = 110.0f,
          i2End = 210.0f,
      )

  val existingFiles =
      Path("sumo_data/gridTrafficScenarios/scenarios")
          .toFile()
          .listFiles()
          ?.map { it.name }
          ?.toSet() ?: emptySet()
  if (existingFiles.size >= 196_608) {
    println("Skipping generation: all 196,608 scenarios already exist.")
    return emptyList()
  }

  var allScenarios = generator.generateAll().toList()

  var done = 0

  val scenario =
      allScenarios.first { generatedScenario ->
        generatedScenario.spawnAt(BOTTOM_ROW, LEFT_LANE)?.type == GridVehicleType.SPEEDY &&
            generatedScenario.spawnAt(BOTTOM_ROW, CENTER_LANE)?.type == GridVehicleType.SPEEDY &&
            generatedScenario.spawnAt(BOTTOM_ROW, RIGHT_LANE)?.type == GridVehicleType.SPEEDY &&
            generatedScenario.spawnAt(MIDDLE_ROW, LEFT_LANE)?.type == GridVehicleType.CALM &&
            generatedScenario.spawnAt(MIDDLE_ROW, CENTER_LANE)?.type == GridVehicleType.EGO &&
            generatedScenario.spawnAt(MIDDLE_ROW, RIGHT_LANE)?.type == GridVehicleType.CALM &&
            generatedScenario.spawnAt(TOP_ROW, LEFT_LANE)?.type == GridVehicleType.CALM &&
            generatedScenario.spawnAt(TOP_ROW, CENTER_LANE)?.type == GridVehicleType.CALM &&
            generatedScenario.spawnAt(TOP_ROW, RIGHT_LANE)?.type == GridVehicleType.CALM
      }
  allScenarios = listOf(scenario)

  val total = allScenarios.size
  println("Generating and writing ${allScenarios.size} scenarios...")

  val pb = ConsoleProgress(total, label = "Grid Traffic Generator")
  pb.render(0, "starting")
  allScenarios.forEach { scenario ->
    scenario.writeRouXml(Path("sumo_data/gridTrafficScenarios/scenarios/scenario.rou.xml"), changeEgoTypeTo = "mutant1")
    done++
    pb.step()
  }
  return allScenarios
}
