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

package tools.aqua.stars.data.sumo.libSumo

import java.nio.file.Path
import java.util.ArrayList
import java.util.UUID
import kotlin.io.path.Path
import kotlin.random.Random
import org.eclipse.sumo.libsumo.Route
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringVector
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficScenariosEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.smallStaticTsc
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.RoadNetwork
import tools.aqua.stars.data.sumo.xml.SumoImporter
import tools.aqua.stars.data.sumo.xml.importer.VehicleTypesFile

/**
 * Minimal libsumo runner for the 3-lane highway scenario.
 *
 * It reproduces the behavior of the .rou.xml directly in Kotlin:
 * - one route on edge "highway"
 * - random spawning on each lane
 * - lane-specific vehicle-type probabilities
 * - configurable density by vehsPerHour per lane
 * - configurable random seed
 */
class LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
    baseDir: Path = Path(EXPERIMENT_DIR),
    netFileName: String = NETWORK_FILE_NAME,
    vTypeAdditionalFile: String = "vTypes.add.xml",
    val tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> =
        smallStaticTsc(),
    val tscId: UUID,
    val stepLength: Double = 0.1,
) {
  private val netFilePath: Path = baseDir.resolve(netFileName)
  private val vTypesFile: Path = baseDir.resolve(vTypeAdditionalFile)
  private val roadNetwork: RoadNetwork = SumoImporter.loadRoadNetwork(netFilePath)
  private val laneById: Map<String, Lane> = roadNetwork.laneById
  private val vehicleTypesFile: VehicleTypesFile = SumoImporter.parseVehicleTypesAddFile(vTypesFile)
  private val vehicleTypesById = vehicleTypesFile.vehicleTypeById

  private data class LaneSpawnConfig(
      val laneIndex: Int,
      val probabilities: List<Pair<String, Double>>,
  )

  private val laneSpawnConfigs =
      listOf(
          LaneSpawnConfig(
              laneIndex = LEFT_LANE,
              probabilities =
                  listOf(
                      "car_speedy" to 0.50,
                      "car_normal" to 0.40,
                      "car_calm" to 0.10,
                  ),
          ),
          LaneSpawnConfig(
              laneIndex = CENTER_LANE,
              probabilities =
                  listOf(
                      "car_speedy" to 0.30,
                      "car_normal" to 0.40,
                      "car_calm" to 0.30,
                  ),
          ),
          LaneSpawnConfig(
              laneIndex = RIGHT_LANE,
              probabilities =
                  listOf(
                      "car_speedy" to 0.10,
                      "car_normal" to 0.40,
                      "car_calm" to 0.50,
                  ),
          ),
      )

  /**
   * Runs the highway traffic scenario.
   *
   * SUMO itself handles the actual car-following and lane changes after insertion. This code only
   * decides when a vehicle is spawned, on which lane, and of which type.
   */
  fun runHighwayTraffic(seed: Int, crowdiness: Int) {
    Simulation.preloadLibraries()

    val baseArgs =
        mutableListOf(
            "--net-file",
            netFilePath.toAbsolutePath().toString(),
            "--additional-files",
            vTypesFile.toAbsolutePath().toString(),
            "--step-length",
            stepLength.toString(),
            "--route-steps",
            "0",
            "--seed",
            seed.toString(),
            "--no-warnings",
            "--collision.action",
            "warn",
            //            "--fcd-output",
            //            Path(FCD_DIR).toAbsolutePath().toString().plus("/$FCD_REPLAY_FILE_NAME"),
        )

    Simulation.load(StringVector(baseArgs.toTypedArray()))
    Route.add("r_highway", StringVector(arrayOf("highway")))

    val rng = Random(seed)
    val perTickSpawnProbability = crowdiness * stepLength / 3600.0

    var nextVehicleNumber = 0L

    while (Simulation.getTime() <= 4.0) {
      val now = Simulation.getTime()
      for (laneConfig in laneSpawnConfigs) {
        if (rng.nextDouble() < perTickSpawnProbability) {
          val vehicleType = sampleVehicleType(rng, laneConfig.probabilities)
          val vehicleId = "veh_${seed}_${laneConfig.laneIndex}_${nextVehicleNumber++}"

          SumoVehicle.add(
              vehicleId,
              "r_highway",
              vehicleType,
              now.toString(),
              laneConfig.laneIndex.toString(),
              "base",
              "max",
          )
        }
      }

      Simulation.step()
    }

    val vehicleIds = SumoVehicle.getIDList()

    val ticks = vehicleIds.map { vehId -> getCurrentTimeStep(vehId, seed, crowdiness) }
    val tickToTscInstancesMap = ticks.map { it to tsc.evaluate(it) }
    var tickToTscInstanceIdMap: Map<TimeStep, UUID?> = emptyMap()
    db {
      tickToTscInstanceIdMap =
          tickToTscInstancesMap
              .map { (tick, tscInstance) ->
                tick to
                    TSCInstancesRepository.getByInstanceJson(tscInstance.getJsonString(), tscId)?.id
              }
              .toMap()
    }
    tickToTscInstancesMap.forEach { (_, instance) ->
      if (instance.rootNode.validate().any()) {
        error("Found invalid TSC Instance: $instance in ${instance.sourceIdentifier}")
      }
    }
    HighwayTrafficScenariosRepository.batchInsert(
        tickToTscInstanceIdMap.map { (tick, tscInstanceId) ->
          HighwayTrafficScenariosEntry(
              seed = seed,
              crowdiness = crowdiness,
              vehicleId = tick.ego.vehicleId,
              vehicleType = tick.ego.vehicleType.toString(),
              lane = tick.ego.currentLane.laneIndex,
              speed = tick.ego.speedKmPerHour.toDouble(),
              position = tick.ego.positionOnLaneMeters.toDouble(),
              tscInstanceId = tscInstanceId ?: error("TSC Instance not found"))
        })

    Simulation.close()
  }

  private fun sampleVehicleType(rng: Random, weightedTypes: List<Pair<String, Double>>): String {
    val x = rng.nextDouble()
    var cumulative = 0.0

    for ((typeId, probability) in weightedTypes) {
      cumulative += probability
      if (x < cumulative) return typeId
    }

    return weightedTypes.last().first
  }

  private fun getCurrentTimeStep(egoId: String, seed: Int, crowdiness: Int): TimeStep {
    val simTimeSeconds = Simulation.getTime()
    val tickTimeMillis = (simTimeSeconds * 1000.0).toLong()

    val vehIds = SumoVehicle.getIDList()
    val vehiclesInTick = ArrayList<Vehicle>(vehIds.size)

    var ego: Vehicle? = null

    for (vehId in vehIds) {
      val laneId = SumoVehicle.getLaneID(vehId)
      val lane = laneById[laneId] ?: error("Unknown lane $laneId")

      val typeId = SumoVehicle.getTypeID(vehId)
      var vehicleType: VehicleType
      val speedMs = SumoVehicle.getSpeed(vehId).toFloat()
      val frontPos = SumoVehicle.getLanePosition(vehId).toFloat()
      val accelMs2: Float = SumoVehicle.getAcceleration(vehId).toFloat()

      if (vehId == egoId) {
        vehicleType = VehicleType(vehicleTypesById["ego"] ?: error("Unknown vehicle type $typeId"))
      } else {
        vehicleType = VehicleType(vehicleTypesById[typeId] ?: error("Unknown vehicle type $typeId"))
      }

      val decelMs2 = 4.5f
      val emergencyDecelMs2 = 9.0f
      val lengthMeters: Float =
          runCatching { SumoVehicle.getLength(vehId).toFloat() }.getOrElse { 0.0f }

      val backPos = frontPos - lengthMeters

      vehiclesInTick +=
          Vehicle(
                  vehicleId = vehId,
                  vehicleType = vehicleType,
                  currentLane = lane,
                  currentEdge = lane.parentEdge,
                  positionOnLaneMeters = frontPos, // keep existing semantics (front bumper)
                  speedMetersPerSecond = speedMs,
                  accelerationMetersPerSecondSquared = accelMs2,
                  frontBumperPositionOnLaneMeters = frontPos,
                  backBumperPositionOnLaneMeters = backPos,
                  decelMetersPerSecondSquared = decelMs2,
                  emergencyDecelMetersPerSecondSquared = emergencyDecelMs2,
              )
              .apply { if (vehId == egoId) ego = this }
    }
    checkNotNull(ego) { "Ego not found" }

    return TimeStep(
        runId = UUID.randomUUID(),
        identifier = "",
        scenarioConfigId = UUID.randomUUID(),
        sourceIdentifier = "$seed $crowdiness ${ego.vehicleId}",
        tickTimeMillis = tickTimeMillis,
        vehiclesInTick = vehiclesInTick,
        collisionsInTick = emptyList(),
        mutantId = UUID.randomUUID(),
        ego = ego)
  }
}
