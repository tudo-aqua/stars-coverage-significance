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

package tools.aqua.stars.coverage.significance.highayTrafficAnalysis

import java.nio.file.Path
import java.util.ArrayList
import kotlin.io.path.Path
import kotlin.math.ln
import kotlin.random.Random
import org.eclipse.sumo.libsumo.Route
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringVector
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.FCD_DIR
import tools.aqua.stars.coverage.significance.FCD_REPLAY_FILE_NAME
import tools.aqua.stars.coverage.significance.HIGHWAY_TRAFFIC_EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficScenariosEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.tsc.tsc
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress
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

/** Main entry point for the highway traffic analysis. */
fun main() {

  DbBootstrap.connect()

  HighwayTrafficScenariosRepository.clear()

  val tsc = tsc()

  val tscEntryId = TSCsRepository.getByJson(tsc.getJsonString())?.id
  checkNotNull(tscEntryId) { "TSC not found in DB; run PrepareDatabaseAndSeed first." }
  val collector =
      LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
          tsc = tsc,
          tscId = tscEntryId,
      )

  val tscInstances = mutableListOf<TSCInstance<*, *, *, *>>()
  val seedCap = 100
  val consoleProgress = ConsoleProgress(total = seedCap)
  for (seed in 1..seedCap) {
    tscInstances.addAll(
        collector.runHighwayTraffic(
            seed = seed,
            crowdiness = 400,
        ))
    consoleProgress.step()
  }

  tscInstances
      .groupingBy { it }
      .eachCount()
      .toList()
      .sortedByDescending { (_, count) -> count }
      .forEach { (tscInstance, instances) ->
        println("TSCInstance: $tscInstance")
        println("Instances: $instances")
        println("-----------")
      }
}

/**
 * A data collector for highway traffic scenarios using SUMO.
 *
 * This class is responsible for generating highway traffic scenarios using SUMO and storing the
 * generated data in a database. It supports running simulations with different crowdiness levels
 * and different driving archetypes.
 *
 * @param baseDir The base directory where the SUMO network and additional files are located.
 * @param netFileName The name of the SUMO network file.
 * @param vTypeAdditionalFile The name of the additional file containing vehicle types.
 * @property tsc The [TSC] instance to use for generating traffic scenarios.
 * @property tscId The ID of the [TSC] instance.
 * @property stepLength The length of each time step in seconds.
 * @property simulationDurationSeconds The duration of the simulation in seconds.
 * @property snapshotIntervalSeconds The interval at which snapshots are taken in seconds.
 * @property minAnalysisDistanceMeters The minimum distance in meters at which to analyze vehicles.
 */
class LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
    baseDir: Path = Path(HIGHWAY_TRAFFIC_EXPERIMENT_DIR),
    netFileName: String = NETWORK_FILE_NAME,
    vTypeAdditionalFile: String = "vTypesHighway.add.xml",
    val tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> = tsc(),
    val tscId: Int,
    val stepLength: Double = 0.1,
    val simulationDurationSeconds: Double = 120.0,
    val snapshotIntervalSeconds: Double = 20.0,
    val minAnalysisDistanceMeters: Float = 150.0f,
) {
  private val netFilePath: Path = baseDir.resolve(netFileName)
  private val vTypesFile: Path = baseDir.resolve(vTypeAdditionalFile)
  private val roadNetwork: RoadNetwork = SumoImporter.loadRoadNetwork(netFilePath)
  private val laneById: Map<String, Lane> = roadNetwork.laneById
  private val vehicleTypesFile: VehicleTypesFile = SumoImporter.parseVehicleTypesAddFile(vTypesFile)
  private val vehicleTypesById = vehicleTypesFile.vehicleTypeById

  private enum class DriverArchetype {
    CALM,
    NORMAL,
    SPEEDY,
  }

  private data class LaneWeights(
      val center: Double,
      val right: Double,
  ) {
    fun toWeightedLanes(): List<Pair<Int, Double>> =
        listOf(
            CENTER_LANE to center,
            RIGHT_LANE to right,
        )
  }

  private val vehicleTypeByArchetype =
      mapOf(
          DriverArchetype.CALM to "car_calm",
          DriverArchetype.NORMAL to "car_normal",
          DriverArchetype.SPEEDY to "car_speedy",
      )

  private val freeFlowLaneWeightsByArchetype =
      mapOf(
          DriverArchetype.CALM to LaneWeights(center = 0.06, right = 0.94),
          DriverArchetype.NORMAL to LaneWeights(center = 0.16, right = 0.84),
          DriverArchetype.SPEEDY to LaneWeights(center = 0.30, right = 0.70),
      )

  private val denseLaneWeightsByArchetype =
      mapOf(
          DriverArchetype.CALM to LaneWeights(center = 0.14, right = 0.86),
          DriverArchetype.NORMAL to LaneWeights(center = 0.28, right = 0.72),
          DriverArchetype.SPEEDY to LaneWeights(center = 0.42, right = 0.58),
      )

  /**
   * Runs the highway traffic simulation.
   *
   * @param seed The seed for the random number generator.
   * @param crowdiness The crowdiness of the traffic.
   * @return A list of [TSCInstance]s representing the generated traffic scenarios.
   */
  fun runHighwayTraffic(seed: Int, crowdiness: Int): List<TSCInstance<*, *, *, *>> {
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
            "--fcd-output",
            Path(FCD_DIR).toAbsolutePath().toString().plus("/$FCD_REPLAY_FILE_NAME"),
        )

    Simulation.load(StringVector(baseArgs.toTypedArray()))
    Route.add("r_highway", StringVector(arrayOf("highway")))

    val rng = Random(seed)
    val normalizedDensity = (crowdiness / 1200.0).coerceIn(0.0, 1.0)
    val totalArrivalRateVehPerHour = crowdiness * 3.0

    var nextVehicleNumber = 0L
    var nextSpawnTimeSeconds =
        sampleInterArrivalSeconds(
            rng = rng,
            totalArrivalRateVehPerHour = totalArrivalRateVehPerHour,
        )

    val tickToTscInstancesMap = mutableListOf<Pair<TimeStep, TSCInstance<*, *, *, *>>>()
    var nextSnapshotTimeSeconds = snapshotIntervalSeconds

    while (Simulation.getTime() <= simulationDurationSeconds) {
      val now = Simulation.getTime()
      while (nextSpawnTimeSeconds <= now) {
        val archetype = sampleDriverArchetype(rng, normalizedDensity)
        val laneOccupancy = getLaneOccupancyRatios()
        val laneIndex = sampleSpawnLane(rng, archetype, normalizedDensity, laneOccupancy)
        val vehicleType = vehicleTypeByArchetype.getValue(archetype)
        val vehicleId = "veh_${seed}_${laneIndex}_${nextVehicleNumber++}"

        runCatching {
          SumoVehicle.add(
              vehicleId,
              "r_highway",
              vehicleType,
              nextSpawnTimeSeconds.toString(),
              laneIndex.toString(),
              "base",
              "max",
          )
        }

        nextSpawnTimeSeconds +=
            sampleInterArrivalSeconds(
                rng = rng,
                totalArrivalRateVehPerHour = totalArrivalRateVehPerHour,
            )
      }

      Simulation.step()

      if (Simulation.getCollisions().isNotEmpty()) break

      val simTimeSeconds = Simulation.getTime()
      while (simTimeSeconds >= nextSnapshotTimeSeconds &&
          nextSnapshotTimeSeconds <= simulationDurationSeconds) {
        val eligibleVehicleIds =
            SumoVehicle.getIDList().filter { vehId ->
              runCatching { SumoVehicle.getLanePosition(vehId).toFloat() }.getOrDefault(0.0f) >=
                  minAnalysisDistanceMeters
            }

        eligibleVehicleIds.forEach { vehId ->
          val tick =
              getCurrentTimeStep(
                  egoId = vehId,
                  seed = seed,
                  crowdiness = crowdiness,
                  snapshotTimeSeconds = nextSnapshotTimeSeconds,
              )
          tickToTscInstancesMap += tick to tsc.evaluate(tick)
        }

        nextSnapshotTimeSeconds += snapshotIntervalSeconds
      }
    }

    var tickToTscInstanceIdMap: Map<TimeStep, Int?> = emptyMap()
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
              tick = tick.tickTimeMillis,
              lane = tick.ego.currentLane.laneIndex,
              speed = tick.ego.speedKmPerHour,
              position = tick.ego.positionOnLaneMeters,
              tscInstanceId = tscInstanceId ?: error("TSC Instance not found"),
          )
        })

    Simulation.close()
    return tickToTscInstancesMap.map { (_, instance) -> instance }
  }

  private fun sampleDriverArchetype(rng: Random, normalizedDensity: Double): DriverArchetype {
    val calmProbability = lerp(start = 0.48, end = 0.28, fraction = normalizedDensity)
    val normalProbability = lerp(start = 0.40, end = 0.47, fraction = normalizedDensity)
    val speedyProbability = 1.0 - calmProbability - normalProbability

    return sampleWeighted(
        rng,
        listOf(
            DriverArchetype.CALM to calmProbability,
            DriverArchetype.NORMAL to normalProbability,
            DriverArchetype.SPEEDY to speedyProbability,
        ),
    )
  }

  private fun sampleSpawnLane(
      rng: Random,
      archetype: DriverArchetype,
      normalizedDensity: Double,
      laneOccupancy: Map<Int, Double>,
  ): Int {
    val freeFlowWeights = freeFlowLaneWeightsByArchetype.getValue(archetype)
    val denseWeights = denseLaneWeightsByArchetype.getValue(archetype)
    val interpolatedWeights =
        LaneWeights(
            center = lerp(freeFlowWeights.center, denseWeights.center, normalizedDensity),
            right = lerp(freeFlowWeights.right, denseWeights.right, normalizedDensity),
        )

    val weightedLanes =
        interpolatedWeights.toWeightedLanes().map { (laneIndex, baseWeight) ->
          val occupancyPenalty = 1.0 - 0.45 * laneOccupancy.getOrElse(laneIndex) { 0.0 }
          val extraRightBias = if (laneIndex == RIGHT_LANE) 1.08 else 1.0
          laneIndex to (baseWeight * occupancyPenalty * extraRightBias).coerceAtLeast(0.0)
        }

    return sampleWeighted(rng, weightedLanes)
  }

  private fun getLaneOccupancyRatios(): Map<Int, Double> {
    val vehicleIds = SumoVehicle.getIDList()
    if (vehicleIds.isEmpty()) {
      return mapOf(LEFT_LANE to 0.0, CENTER_LANE to 0.0, RIGHT_LANE to 0.0)
    }

    val countsByLane = mutableMapOf(LEFT_LANE to 0, CENTER_LANE to 0, RIGHT_LANE to 0)
    for (vehicleId in vehicleIds) {
      val laneId = runCatching { SumoVehicle.getLaneID(vehicleId) }.getOrNull() ?: continue
      val laneIndex = laneById[laneId]?.laneIndex ?: continue
      countsByLane.computeIfPresent(laneIndex) { _, count -> count + 1 }
    }

    val totalVehicles = countsByLane.values.sum().coerceAtLeast(1)
    return countsByLane.mapValues { (_, count) -> count.toDouble() / totalVehicles.toDouble() }
  }

  private fun sampleInterArrivalSeconds(
      rng: Random,
      totalArrivalRateVehPerHour: Double,
  ): Double {
    val meanInterArrivalSeconds = 3600.0 / totalArrivalRateVehPerHour.coerceAtLeast(1.0)
    return sampleExponentialSeconds(rng, meanInterArrivalSeconds)
  }

  private fun sampleExponentialSeconds(rng: Random, meanSeconds: Double): Double {
    val u = rng.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
    return -meanSeconds * ln(1.0 - u)
  }

  private fun <T> sampleWeighted(rng: Random, weightedValues: List<Pair<T, Double>>): T {
    val sanitizedWeights = weightedValues.map { it.first to it.second.coerceAtLeast(0.0) }
    val totalWeight = sanitizedWeights.sumOf { it.second }
    check(totalWeight > 0.0) { "At least one positive weight is required." }

    var draw = rng.nextDouble() * totalWeight
    for ((value, weight) in sanitizedWeights) {
      draw -= weight
      if (draw <= 0.0) return value
    }
    return sanitizedWeights.last().first
  }

  private fun lerp(start: Double, end: Double, fraction: Double): Double =
      start + (end - start) * fraction.coerceIn(0.0, 1.0)

  private fun getCurrentTimeStep(
      egoId: String,
      seed: Int,
      crowdiness: Int,
      snapshotTimeSeconds: Double = Simulation.getTime(),
  ): TimeStep {
    val tickTimeMillis = (snapshotTimeSeconds * 1000.0).toLong()

    val vehIds = SumoVehicle.getIDList()
    val vehiclesInTick = ArrayList<Vehicle>(vehIds.size)

    var ego: Vehicle? = null

    for (vehId in vehIds) {
      val laneId = SumoVehicle.getLaneID(vehId)
      val lane = laneById[laneId] ?: error("Unknown lane $laneId")

      val typeId = SumoVehicle.getTypeID(vehId)
      val vehicleType =
          if (vehId == egoId) {
            VehicleType(vehicleTypesById["ego"] ?: error("Unknown vehicle type ego"))
          } else {
            VehicleType(vehicleTypesById[typeId] ?: error("Unknown vehicle type $typeId"))
          }
      val speedMs = SumoVehicle.getSpeed(vehId).toFloat()
      val frontPos = SumoVehicle.getLanePosition(vehId).toFloat()
      val accelMs2: Float = SumoVehicle.getAcceleration(vehId).toFloat()

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
                  positionOnLaneMeters = frontPos,
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
        runId = 0,
        identifier = "",
        scenarioConfigId = 0,
        sourceIdentifier = "$seed $crowdiness ${ego.vehicleId} t=$tickTimeMillis",
        tickTimeMillis = tickTimeMillis,
        vehiclesInTick = vehiclesInTick,
        collisionsInTick = emptyList(),
        mutantId = 0,
        ego = ego,
        egoManeuver = null)
  }
}
