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

package tools.aqua.stars.coverage.significance.gridTrafficGenerator

import kotlin.io.path.Path
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.sumo.cleanGenerationFiles
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

/**
 * Function to get grid traffic scenarios.
 *
 * @param n Optional number of scenarios to generate; if null, generates all scenarios.
 * @param seed Seed for random number generation.
 * @param enablePositionVariance Whether to enable position variance sampling.
 * @param insertIntoDatabase Whether to insert the scenarios into the database.
 * @return List of generated scenarios.
 */
fun seedGridTrafficScenarios(
    n: Int? = null,
    seed: Int = 1,
    enablePositionVariance: Boolean = false,
    insertIntoDatabase: Boolean = true
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

  var allScenarios = generator.generateAll().toList()
  if (n != null && n < allScenarios.size) {
    allScenarios = allScenarios.shuffled(rng).take(n)
  }

  val countOfScenarios =
      if (insertIntoDatabase) ScenarioStartingConfigurationRepository.getCount() else 0

  // Table is already populated.
  if (countOfScenarios == allScenarios.size.toLong()) {
    println("All scenarios already exist; skipping generation.")
    return allScenarios
  }

  if (insertIntoDatabase) {
    ScenarioStartingConfigurationRepository.clearTable()
    ScenarioStartingConfigurationRepository.batchInsert(
        allScenarios.map { it.toScenarioStartingConfigurationEntry() })
  }
  return allScenarios
}

/**
 * Function to generate grid traffic scenarios.
 *
 * @param n Optional number of scenarios to generate; if null, generates all scenarios.
 * @param seed Seed for random number generation.
 * @param enablePositionVariance Whether to enable position variance sampling.
 * @param insertIntoDatabase Whether to insert the scenarios into the database.
 * @param cleanGenerationFiles Whether to clean the generation files directory before generating.
 */
fun generateGridTrafficScenarios(
    n: Int? = null,
    seed: Int = 1,
    enablePositionVariance: Boolean = false,
    insertIntoDatabase: Boolean = true,
    cleanGenerationFiles: Boolean = true
) {
  if (cleanGenerationFiles) {
    cleanGenerationFiles()
  }
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
    return
  }

  var allScenarios = generator.generateAll().toList()

  var done = 0

  if (n != null && n < allScenarios.size) {
    allScenarios = allScenarios.shuffled(rng).take(n)
  }
  val total = allScenarios.size
  println("Generating and writing ${allScenarios.size} scenarios...")

  val pb = ConsoleProgress(total, label = "Grid Traffic Generator")
  pb.render(0, "starting")
  allScenarios.forEach { scenario ->
    scenario.writeRouXml(Path("sumo_data/gridTrafficScenarios/scenarios/${scenario.id}.rou.xml"))
    if (insertIntoDatabase) {
      ScenarioStartingConfigurationRepository.insertIfMissing(
          scenario.toScenarioStartingConfigurationEntry())
    }
    done++
    pb.step()
  }
}
