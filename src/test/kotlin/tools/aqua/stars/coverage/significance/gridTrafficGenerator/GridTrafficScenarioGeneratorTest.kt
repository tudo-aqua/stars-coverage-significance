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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.MAX_SPEED_ABOVE_START_SPEED_KMH
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.CALM
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.NORMAL
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.SPEEDY
import tools.aqua.stars.data.sumo.xml.SumoImporter

/** Tests for [GridTrafficScenarioGenerator]. */
class GridTrafficScenarioGeneratorTest {

  /** A small configuration: 3 ego lanes x 1 ego speed x 3^8 cell patterns = 19,683. */
  private val small =
      GridTrafficScenarioGenerator(
          egoSpeedsKmh = listOf(100),
          otherSpeedsKmh = listOf(70, 130),
          typeBySpeedKmh = mapOf(70 to CALM, 130 to SPEEDY))

  /** The default configuration enumerates 3 x 3 x 5^8 configurations. */
  @Test
  fun `the default configuration has 3,515,625 scenarios`() {
    val generator = GridTrafficScenarioGenerator()
    assertEquals(390_625L, generator.patternCount)
    assertEquals(3_515_625L, generator.configurationCount)
    assertEquals(3_515_625L, generator.scenarioCount)
    assertEquals(7_031_241L, generator.copy(positionDraws = 2).scenarioCount)
    assertEquals(10_546_857L, generator.copy(positionDraws = 3).scenarioCount)
  }

  /** Every index yields one scenario, except later draws of ego-only configurations. */
  @Test
  fun `a small configuration with two draws yields every scenario once`() {
    val generator = small.copy(positionDraws = 2)
    val scenarios = generator.generateAll().toList()

    assertEquals(2 * 19_683L - 3, generator.scenarioCount)
    assertEquals(generator.scenarioCount, scenarios.size.toLong())
    assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size, "ids are not unique")
    assertEquals(
        generator.configurationCount, scenarios.count { it.positionDrawIndex == 0 }.toLong())
    scenarios.forEachIndexed { position, scenario ->
      assertEquals(
          position.toLong(), generator.scenariosBefore(checkNotNull(scenario.enumerationIndex)))
    }
    scenarios.forEach { assertStartConditions(generator, it) }
    assertNull(generator.scenarioAt(generator.configurationCount), "ego-only configuration, draw 1")
  }

  /** The enumeration index decodes into ego speed, ego lane and one speed digit per cell. */
  @Test
  fun `the enumeration index encodes ego speed, ego lane and the speed in every cell`() {
    val generator = GridTrafficScenarioGenerator()
    val random = Random(7)
    repeat(2_000) {
      val index = random.nextLong(generator.indexCount)
      val scenario = assertNotNull(generator.scenarioAt(index))
      assertEquals(index, scenario.enumerationIndex)
      assertEquals(0, scenario.positionDrawIndex)

      val egoSlot = index / generator.patternCount
      assertEquals((egoSlot % GRID_SIZE).toInt(), scenario.egoLane)
      assertEquals(
          generator.egoSpeedsKmh[(egoSlot / GRID_SIZE).toInt()],
          assertNotNull(scenario.spawnAt(MIDDLE_ROW, scenario.egoLane)).speedKmh)

      var digits = index % generator.patternCount
      for (cell in 0 until CELL_COUNT) {
        if (cell == cellIndex(MIDDLE_ROW, scenario.egoLane)) continue
        val digit = (digits % (generator.otherSpeedsKmh.size + 1)).toInt()
        digits /= generator.otherSpeedsKmh.size + 1
        val spawn = scenario.spawnAt(cell / GRID_SIZE, cell % GRID_SIZE)
        if (digit == 0) {
          assertNull(spawn)
        } else {
          val vehicle = assertNotNull(spawn)
          assertEquals(generator.otherSpeedsKmh[digit - 1], vehicle.speedKmh)
          assertEquals(generator.typeBySpeedKmh[vehicle.speedKmh], vehicle.type)
        }
      }
      assertStartConditions(generator, scenario)
    }
  }

  /** Positions depend only on the seed and the index. */
  @Test
  fun `the same seed gives the same scenarios and another seed different positions`() {
    val indices = (0 until 200).map { it * 17_573L }
    val first = indices.map { GridTrafficScenarioGenerator().scenarioAt(it) }
    val second = indices.map { GridTrafficScenarioGenerator().scenarioAt(it) }
    val otherSeed = indices.map { GridTrafficScenarioGenerator(seed = 2).scenarioAt(it) }

    assertEquals(first, second)
    assertNotEquals(first.map { it?.id }, otherSeed.map { it?.id })
  }

  /** Every draw of a configuration differs from the earlier draws. */
  @Test
  fun `the draws of a configuration differ`() {
    val generator = GridTrafficScenarioGenerator(positionDraws = 3)
    val random = Random(11)
    repeat(300) {
      val configuration = random.nextLong(generator.configurationCount)
      val draws =
          (0 until 3).mapNotNull {
            generator.scenarioAt(it * generator.configurationCount + configuration)
          }
      assertEquals(draws.size, draws.map { it.id }.toSet().size)
      draws.forEach { assertStartConditions(generator, it) }
    }
  }

  /** Without variance, every vehicle stands at its cell centre. */
  @Test
  fun `without variance every vehicle stands at its cell centre`() {
    val generator = small.copy(variance = false)
    val centres = listOf(50.0f, 110.0f, 170.0f)
    generator.generateAll().take(5_000).forEach { scenario ->
      scenario.placements.forEach { assertEquals(centres[it.row], it.positionMeters) }
    }
  }

  /** Invalid configurations are rejected. */
  @Test
  fun `invalid configurations are rejected`() {
    assertFailsWith<IllegalArgumentException> {
      GridTrafficScenarioGenerator(otherSpeedsKmh = listOf(70, 80))
    }
    assertFailsWith<IllegalArgumentException> {
      GridTrafficScenarioGenerator(variance = false, positionDraws = 2)
    }
    assertFailsWith<IllegalArgumentException> {
      GridTrafficScenarioGenerator(egoSpeedsKmh = listOf(80, 80))
    }
  }

  /** Command-line arguments override the defaults. */
  @Test
  fun `fromArgs reads every argument`() {
    val generator =
        GridTrafficScenarioGenerator.fromArgs(
            arrayOf(
                "--egoSpeeds=90",
                "--otherSpeeds=60,120",
                "--types=60:CALM, 120:NORMAL",
                "--positionDraws=2",
                "--variance=true",
                "--minStartGap=5",
                "--minStartTtc=3",
                "--maxAttempts=50"))
    assertEquals(
        GridTrafficScenarioGenerator(
            egoSpeedsKmh = listOf(90),
            otherSpeedsKmh = listOf(60, 120),
            typeBySpeedKmh = mapOf(60 to CALM, 120 to NORMAL),
            positionDraws = 2,
            minStartGapMeters = 5.0,
            minStartTtcSeconds = 3.0,
            maxAttempts = 50),
        generator)
    assertEquals(GridTrafficScenarioGenerator(), GridTrafficScenarioGenerator.fromArgs(arrayOf()))
  }

  /** SUMO rejects a start speed above the vType's maximum speed. */
  @Test
  fun `every vehicle type has a vType that admits every configured speed`() {
    val generator = GridTrafficScenarioGenerator()
    listOf(Path("sumoData/experiment/vTypes.add.xml"), Path("src/test/resources/vTypes.add.xml"))
        .forEach { file ->
          val vTypes = SumoImporter.parseVehicleTypesAddFile(file).vehicleTypeById
          generator.typeBySpeedKmh.forEach { (speedKmh, type) ->
            val vType = assertNotNull(vTypes[type.sumoId], "$file has no vType ${type.sumoId}")
            val maxSpeedMps = vType.rawAttributes.getValue("maxSpeed").toDouble()
            assertTrue(
                maxSpeedMps >= (speedKmh + MAX_SPEED_ABOVE_START_SPEED_KMH) / 3.6,
                "${type.sumoId} in $file has maxSpeed $maxSpeedMps m/s, too low for $speedKmh km/h")
          }
        }
  }

  /**
   * Asserts that every vehicle of [scenario] lies in its cell on the 0.1 m grid and that
   * consecutive vehicles on a lane keep [GridTrafficScenarioGenerator.minStartGapMeters] and
   * [GridTrafficScenarioGenerator.minStartTtcSeconds].
   */
  private fun assertStartConditions(
      generator: GridTrafficScenarioGenerator,
      scenario: GeneratedScenario,
  ) {
    val rows =
        listOf(
            generator.i0Start..generator.i0End,
            generator.i1Start..generator.i1End,
            generator.i2Start..generator.i2End)
    scenario.placements.forEach { spawn ->
      assertTrue(spawn.positionMeters in rows[spawn.row], "$spawn outside its row")
      val tenths = spawn.positionMeters * 10
      assertTrue(abs(tenths - tenths.roundToInt()) < 1e-3, "$spawn not on the 0.1 m grid")
    }
    scenario.placements
        .groupBy { it.lane }
        .values
        .forEach { lane ->
          lane
              .sortedBy { it.positionMeters }
              .zipWithNext()
              .forEach { (follower, leader) ->
                val gap =
                    leader.positionMeters - follower.positionMeters - generator.vehicleLengthMeters
                assertTrue(gap >= generator.minStartGapMeters - 1e-6, "gap $gap in ${scenario.id}")
                val closingSpeedMps = follower.placementSpeedMps - leader.placementSpeedMps
                if (closingSpeedMps > 1e-9) {
                  assertTrue(
                      gap / closingSpeedMps >= generator.minStartTtcSeconds - 1e-6,
                      "time to collision ${gap / closingSpeedMps} in ${scenario.id}")
                }
              }
        }
  }
}
