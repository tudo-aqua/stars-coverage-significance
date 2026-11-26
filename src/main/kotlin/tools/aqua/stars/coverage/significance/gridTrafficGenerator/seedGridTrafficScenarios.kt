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

import java.util.Locale
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

/** Default number of scenarios per insert transaction. */
const val DEFAULT_SEED_BATCH_SIZE = 10_000

/** Number of scenarios between two progress lines of [printGridTrafficScenarioStatistics]. */
private const val DRY_RUN_PROGRESS_INTERVAL = 500_000L

/**
 * Seeds `scenario_starting_configurations` with the scenarios of [generator], in enumeration order.
 *
 * The table is never cleared, because the result tables reference it with `ON DELETE CASCADE`. If
 * it already holds scenarios, seeding continues after the last stored enumeration index, once that
 * row and the row count have been checked against [generator]. A table filled with another
 * generator configuration fails this check instead of being overwritten.
 *
 * @param generator The scenario generator.
 * @param batchSize Number of scenarios per insert transaction.
 */
fun seedGridTrafficScenarios(
    generator: GridTrafficScenarioGenerator,
    batchSize: Int = DEFAULT_SEED_BATCH_SIZE,
) {
  require(batchSize > 0) { "batchSize must be positive but was $batchSize" }
  println("Seeding scenarios: $generator")
  println(generator.countSummary())

  val startIndex = resumeIndex(generator)
  val remaining = generator.scenarioCount - generator.scenariosBefore(startIndex)
  if (remaining == 0L) {
    println("All ${generator.scenarioCount} scenarios already exist; skipping generation.")
    return
  }

  val statistics = DrawStatistics()
  val progress =
      ConsoleProgress(
          ((remaining + batchSize - 1) / batchSize).toInt(), label = "Seeding scenarios")
  generator.generateAll(startIndex, statistics).chunked(batchSize).forEach { batch ->
    ScenarioStartingConfigurationRepository.batchInsert(
        batch.map { it.toScenarioStartingConfigurationEntry() })
    progress.step()
  }
  println("Seeded $remaining scenarios: $statistics")
}

/**
 * Returns the first enumeration index that is not stored yet: 0 for an empty table, otherwise the
 * index after the last stored scenario, which must match [generator].
 */
private fun resumeIndex(generator: GridTrafficScenarioGenerator): Long {
  val last = ScenarioStartingConfigurationRepository.getLastInserted() ?: return 0L
  val lastIndex = checkNotNull(last.enumerationIndex) { "Stored scenario without index: $last" }
  val expected =
      if (lastIndex in 0 until generator.indexCount) generator.scenarioAt(lastIndex) else null
  check(expected?.buildHumanReadableId() == last.humanReadableScenarioId) {
    "The last stored scenario (seq ${last.sequenceNumber}, enumeration index $lastIndex) does not " +
        "match this generator configuration. Seed into a fresh database, or use the configuration " +
        "that filled this table."
  }
  val stored = ScenarioStartingConfigurationRepository.getCount()
  val expectedCount = generator.scenariosBefore(lastIndex + 1)
  check(stored == expectedCount) {
    "The table holds $stored scenarios, but the enumeration up to index $lastIndex has " +
        "$expectedCount. The table is incomplete or was not filled in enumeration order."
  }
  println("Resuming after enumeration index $lastIndex ($stored scenarios stored).")
  return lastIndex + 1
}

/**
 * Generates all scenarios of [generator] without touching the database and prints their counts, the
 * rejection rate of the position draws, and the smallest bumper gap and time to collision between
 * consecutive vehicles on a lane.
 *
 * @param generator The scenario generator.
 */
fun printGridTrafficScenarioStatistics(generator: GridTrafficScenarioGenerator) {
  println("Dry run: $generator")
  println(generator.countSummary())

  val statistics = DrawStatistics()
  val countByEgoSpeed = sortedMapOf<Int, Long>()
  val countByVehicleCount = sortedMapOf<Int, Long>()
  var minGapMeters = Double.POSITIVE_INFINITY
  var minTtcSeconds = Double.POSITIVE_INFINITY
  var count = 0L

  generator.generateAll(statistics = statistics).forEach { scenario ->
    count++
    val ego = checkNotNull(scenario.spawnAt(MIDDLE_ROW, scenario.egoLane))
    countByEgoSpeed.merge(ego.speedKmh, 1L, Long::plus)
    countByVehicleCount.merge(scenario.vehiclesCount() - 1, 1L, Long::plus)
    scenario.placements
        .groupBy { it.lane }
        .values
        .forEach { laneSpawns ->
          laneSpawns
              .sortedBy { it.positionMeters }
              .zipWithNext()
              .forEach { (follower, leader) ->
                val gap =
                    leader.positionMeters - follower.positionMeters - generator.vehicleLengthMeters
                minGapMeters = minOf(minGapMeters, gap)
                val closingSpeedMps = follower.placementSpeedMps - leader.placementSpeedMps
                if (closingSpeedMps > 0.0)
                    minTtcSeconds = minOf(minTtcSeconds, gap / closingSpeedMps)
              }
        }
    if (count % DRY_RUN_PROGRESS_INTERVAL == 0L) println("$count scenarios generated")
  }

  check(count == generator.scenarioCount) {
    "Generated $count scenarios, but the generator reports ${generator.scenarioCount}"
  }
  println("Generated $count scenarios: $statistics")
  println("Scenarios by ego speed (km/h): $countByEgoSpeed")
  println("Scenarios by number of other vehicles: $countByVehicleCount")
  println(
      "Smallest start bumper gap: ${"%.2f".format(Locale.US, minGapMeters)} m, smallest start " +
          "time to collision: ${"%.2f".format(Locale.US, minTtcSeconds)} s")
}
