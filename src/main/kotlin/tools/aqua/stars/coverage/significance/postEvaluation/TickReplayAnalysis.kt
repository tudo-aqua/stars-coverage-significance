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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickReplayResultExport
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * For one or more recorded ticks (`metric_failed_monitors` rows), reconstructs the local traffic
 * scene from the tick's *relative* neighbour data (see
 * `tools.aqua.stars.data.sumo.libSumo.computeReplayPlacements`), then lets every known mutant
 * separately take control of the ego for exactly one simulated step, to compare what each mutant
 * actually does when faced with that exact scene.
 *
 * Scope: this surfaces the mutant's maneuver command, the resulting next-tick kinematics, and
 * whether a collision occurs. It does **not** re-evaluate TSC monitors (G0-G4/I1/I2) — that
 * requires the full `TSCEvaluation` framework running across a longer window of ticks, which is out
 * of scope here.
 */
object TickReplayAnalysis {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "tick_replay")

  fun evaluate(tickIds: List<Int>) {
    println("Starting TickReplayAnalysis for ticks: $tickIds")

    val mutants = MutantsRepository.listAll()
    println("Replaying against ${mutants.size} mutants.")

    val collector = LibsumoDynamicDataCollector()
    val results = mutableListOf<TickReplayResultExport>()

    for (tickId in tickIds) {
      val tick = MetricFailedMonitorsRepository.getById(tickId)
      if (tick == null) {
        println("  Tick $tickId not found — skipping.")
        continue
      }

      val scenario = ScenarioStartingConfigurationRepository.getById(tick.scenarioConfigId)
      if (scenario == null) {
        println("  Tick $tickId references unknown scenario ${tick.scenarioConfigId} — skipping.")
        continue
      }

      println("  Tick $tickId (tick=${tick.tick}, run=${tick.runId}):")
      for (mutant in mutants) {
        val mutantId = checkNotNull(mutant.id)
        val nextTick = collector.replayTickForMutant(tick.runId, tick, scenario, mutantId)

        val result = toExport(tick, mutant, nextTick)
        results += result

        val maneuverText =
            "speed=${result.maneuverSpeedMps?.let { "%.2f".format(it) }} " +
                "laneChange=${result.maneuverLaneChangeDirection}"
        val outcomeText =
            if (result.collisionOccurred) "COLLISION (${result.collisionType})"
            else "speed=${result.nextTickEgoSpeedMps} lane=${result.nextTickEgoLaneIndex}"
        println(
            "    mutant ${mutant.mutantNumber} (${mutant.className}): $maneuverText -> $outcomeText")
      }
    }

    Files.createDirectories(BASE_PATH)
    val fileName = "tick_replay_${tickIds.joinToString("-")}.json"
    val jsonPath = BASE_PATH.resolve(fileName)
    jsonPath.writeText(jsonConfiguration.encodeToString(results))
    println("Finished TickReplayAnalysis. JSON written to: $jsonPath")
  }

  private fun toExport(
      tick: MetricFailedMonitorsEntry,
      mutant: MutantEntry,
      nextTick: TimeStep?,
  ): TickReplayResultExport {
    val collision = nextTick?.collisionsInTick?.firstOrNull()
    val dist = nextTick?.egoSurroundingVehicleDistances

    return TickReplayResultExport(
        tickId = checkNotNull(tick.id),
        originalTick = tick.tick,
        mutantId = checkNotNull(mutant.id),
        mutantNumber = mutant.mutantNumber,
        className = mutant.className,
        maneuverSpeedMps = nextTick?.egoManeuver?.newSpeedMps,
        maneuverLaneChangeDirection = nextTick?.egoManeuver?.laneChangeDirection?.name,
        collisionOccurred = collision != null,
        collisionType = collision?.collisionType,
        nextTickEgoSpeedMps = nextTick?.ego?.speedMetersPerSecond,
        nextTickEgoLaneIndex = nextTick?.ego?.currentLane?.laneIndex,
        nextTickEgoFrontBumperPosMeters = nextTick?.ego?.frontBumperPositionOnLaneMeters,
        nextTickEgoBackBumperPosMeters = nextTick?.ego?.backBumperPositionOnLaneMeters,
        nextTickSurroundingDistFront = dist?.frontMeters,
        nextTickSurroundingDistRear = dist?.rearMeters,
        nextTickSurroundingDistFrontLeft = dist?.frontLeftMeters,
        nextTickSurroundingDistFrontRight = dist?.frontRightMeters,
        nextTickSurroundingDistRearLeft = dist?.rearLeftMeters,
        nextTickSurroundingDistRearRight = dist?.rearRightMeters,
    )
  }
}
