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
import tools.aqua.stars.coverage.significance.ConsoleProgress
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository

/**
 * Function to generate grid traffic scenarios.
 *
 * @param n Optional number of scenarios to generate; if null, generates all scenarios.
 * @param seed Seed for random number generation.
 */
fun generateGridTrafficScenarios(n: Int? = null, seed: Int = 1) {
  val rng = Random(seed)
  val generator =
      GridTrafficScenarioGenerator(
          enablePositionVariance = false,
          positionVariantsPerOccupancy = 3,
          seed = seed,
          minForwardGapMeters = 75.0,
          i0Start = 0.0,
          i0End = 100.0,
          i1Start = 100.0,
          i1End = 110.0,
          i2Start = 110.0,
          i2End = 210.0,
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
    ScenarioStartingConfigurationRepository.upsert(scenario.toScenarioStartingConfigurationEntry())
    done++
    pb.step()
  }
}
