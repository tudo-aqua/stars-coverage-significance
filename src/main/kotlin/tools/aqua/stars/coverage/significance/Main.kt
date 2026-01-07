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
import tools.aqua.stars.coverage.significance.fullTrafficGenerator.FullTrafficScenarioGenerator
import tools.aqua.stars.coverage.significance.fullTrafficGenerator.FullTrafficVehicleType
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridTrafficScenarioGenerator
import tools.aqua.stars.data.sumo.SumoImporter

/** Generation of scenarios and printing of the TikZ code for the first scenario. */
fun main() {
  val sumoImporter = SumoImporter()
  val scenario =
      sumoImporter.importScenario(
          netFilePath = Path("sumo_data/autobahnCollisions/autobahnCollisions.net.xml"),
          exportFilePath = Path("sumo_data/autobahnCollisions/export_autobahnCollisions.xml"),
          routesFilePath = Path("sumo_data/autobahnCollisions/autobahnCollisions.rou.xml"),
          collisionFilePath = Path("sumo_data/autobahnCollisions/collision_autobahnCollisions.xml"),
      )

  println("Imported scenario with ${scenario.ticks.size} ticks.")

  val tsc = staticTsc()
  println("TSC size: ${tsc.possibleTSCInstances.size}")
  TSCTikzRenderer.render(tsc).let { tikzCode -> println(tikzCode) }

  generateGridTrafficScenarios()
}

/** Function to generate grid traffic scenarios. */
fun generateGridTrafficScenarios() {
  val generator =
      GridTrafficScenarioGenerator(
          enablePositionVariance = false,
          positionVariantsPerOccupancy = 3,
          seed = 1,
          minForwardGapMeters = 50.0,
          egoSpeedKmh = 100,
          calmSpeedKmh = 70,
          normalSpeedKmh = 100,
          speedySpeedKmh = 130,
          i0Start = 0.0,
          i0End = 100.0,
          i1Start = 100.0,
          i1End = 110.0,
          i2Start = 110.0,
          i2End = 210.0,
      )

  val allScenarios = generator.generateAll().toList()
  val s = ""

  allScenarios.take(100).forEach { scenario ->
    println(scenario.toASCIIString())
    println()
    println()
  }
}

/** Function to generate traffic scenarios and print the TikZ code for the first scenario. */
fun generateFullTrafficScenarios() {
  val generator =
      FullTrafficScenarioGenerator(
          scenarioCount = 10_000,
          minNumOfVehicles = 200,
          maxNumOfVehicles = 200,
          numberOfLanes = 3,
          numberOfBlocksPerLane = 100,
          distributionOfVehicleTypes = doubleArrayOf(0.2, 0.4, 0.3, 0.1),
          probabilityOfLaneByFullTrafficVehicleType =
              mapOf(
                  FullTrafficVehicleType.TRUCK to doubleArrayOf(0.70, 0.30, 0.00),
                  FullTrafficVehicleType.CAR_CALM to doubleArrayOf(0.33, 0.34, 0.33),
                  FullTrafficVehicleType.CAR_NORMAL to doubleArrayOf(0.33, 0.34, 0.33),
                  FullTrafficVehicleType.CAR_SPORTY to doubleArrayOf(0.00, 0.40, 0.60),
              ),
          seed = 4)

  val scenarios = generator.generate()

  val first = scenarios.first()
  //  println("vehicles=${first.vehiclesCount()}")
  //  println(TrafficScenarioGenSingleMaskReadable.toCoordinates(first).take(5))

  val tikz = generator.toTikz(first)
  println(tikz)
}
