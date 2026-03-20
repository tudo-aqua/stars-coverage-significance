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
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.FCD_DIR
import tools.aqua.stars.coverage.significance.FCD_REPLAY_FILE_NAME
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficScenariosEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.CENTER_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.LEFT_LANE
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.RIGHT_LANE
import tools.aqua.stars.coverage.significance.smallStaticTsc
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

fun main() {

  DbBootstrap.connect()

  HighwayTrafficScenariosRepository.clear()

  val tsc = smallStaticTsc()

  val collector =
      LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
          tsc = tsc,
          tscId = UUID.fromString("34fcc47b-8cda-4bb0-af8e-860fab472a85"),
      )

  val tscInstances = mutableListOf<TSCInstance<*, *, *, *>>()
  val totalSeeds = 1000
  val consoleProgress = ConsoleProgress(total = totalSeeds)
  for (seed in 1..totalSeeds) {
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
        println("TSCInstance: ${tscInstance}")
        println("Instances: ${instances}")
        println("-----------")
      }
}

/**
 * Minimal libsumo runner for the 3-lane highway scenario.
 *
 * It reproduces the behavior of the .rou.xml directly in Kotlin:
 * - one route on edge "highway"
 * - stochastic total-demand arrivals with platoons/headways
 * - density-dependent lane assignment with keep-right bias
 * - configurable density via vehicles-per-hour baseline
 * - configurable random seed
 */
class LibsumoDynamicDataCollectorForHighwayTrafficAnalysis(
    baseDir: Path = Path(EXPERIMENT_DIR),
    netFileName: String = NETWORK_FILE_NAME,
    vTypeAdditionalFile: String = "vTypesHighway.add.xml",
    val tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> =
        smallStaticTsc(),
    val tscId: UUID,
    val stepLength: Double = 0.1,
    val simulationDurationSeconds: Double = 20.0,
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
      val left: Double,
      val center: Double,
      val right: Double,
  ) {
    fun toWeightedLanes(): List<Pair<Int, Double>> =
        listOf(
            LEFT_LANE to left,
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
          DriverArchetype.CALM to LaneWeights(left = 0.00, center = 0.08, right = 0.92),
          DriverArchetype.NORMAL to LaneWeights(left = 0.02, center = 0.20, right = 0.78),
          DriverArchetype.SPEEDY to LaneWeights(left = 0.10, center = 0.35, right = 0.55),
      )

  private val denseLaneWeightsByArchetype =
      mapOf(
          DriverArchetype.CALM to LaneWeights(left = 0.02, center = 0.18, right = 0.80),
          DriverArchetype.NORMAL to LaneWeights(left = 0.08, center = 0.34, right = 0.58),
          DriverArchetype.SPEEDY to LaneWeights(left = 0.18, center = 0.44, right = 0.38),
      )

  /**
   * Runs the highway traffic scenario.
   *
   * SUMO itself handles the actual car-following and lane changes after insertion. This code only
   * decides when a vehicle is spawned, on which lane, and of which type.
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
    var platoonVehiclesRemaining = 0
    var nextSpawnTimeSeconds =
        sampleInterArrivalSeconds(
            rng = rng,
            totalArrivalRateVehPerHour = totalArrivalRateVehPerHour,
            normalizedDensity = normalizedDensity,
            isFollowingPlatoonVehicle = false,
        )

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
            .onFailure {
              // SUMO occasionally rejects insertions when there is no feasible gap. In that case,
              // skip the arrival and continue with the stochastic process instead of forcing the
              // lane.
            }

        if (platoonVehiclesRemaining > 0) {
          platoonVehiclesRemaining--
        } else if (rng.nextDouble() < platoonProbability(normalizedDensity)) {
          platoonVehiclesRemaining = samplePlatoonSize(rng) - 1
        }

        nextSpawnTimeSeconds +=
            sampleInterArrivalSeconds(
                rng = rng,
                totalArrivalRateVehPerHour = totalArrivalRateVehPerHour,
                normalizedDensity = normalizedDensity,
                isFollowingPlatoonVehicle = platoonVehiclesRemaining > 0,
            )
      }

      Simulation.step()
    }

    val vehicleIds = SumoVehicle.getIDList()
    val eligibleVehicleIds =
        vehicleIds.filter { vehId ->
          runCatching { SumoVehicle.getLanePosition(vehId).toFloat() }.getOrDefault(0.0f) >=
              minAnalysisDistanceMeters
        }

    val ticks = eligibleVehicleIds.map { vehId -> getCurrentTimeStep(vehId, seed, crowdiness) }
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
    return tickToTscInstancesMap.map { (_, instance) -> instance }.toList()
  }

  /**
   * Only evaluate vehicles that have actually developed into highway traffic instead of freshly
   * inserted vehicles near the origin. Since all vehicles depart at the beginning of the route,
   * lane position is an adequate proxy for travelled distance here.
   */
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
            left = lerp(freeFlowWeights.left, denseWeights.left, normalizedDensity),
            center = lerp(freeFlowWeights.center, denseWeights.center, normalizedDensity),
            right = lerp(freeFlowWeights.right, denseWeights.right, normalizedDensity),
        )

    val weightedLanes =
        interpolatedWeights.toWeightedLanes().map { (laneIndex, baseWeight) ->
          val occupancyPenalty = 1.0 - 0.45 * laneOccupancy.getOrElse(laneIndex) { 0.0 }
          laneIndex to (baseWeight * occupancyPenalty).coerceAtLeast(0.0)
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
      normalizedDensity: Double,
      isFollowingPlatoonVehicle: Boolean,
  ): Double {
    if (isFollowingPlatoonVehicle) {
      return rng.nextDouble(
          from = lerp(start = 0.8, end = 0.5, fraction = normalizedDensity),
          until = lerp(start = 2.4, end = 1.4, fraction = normalizedDensity),
      )
    }

    val meanHeadwaySeconds = 3600.0 / totalArrivalRateVehPerHour.coerceAtLeast(1.0)
    val exponentialGap =
        -kotlin.math.ln((1.0 - rng.nextDouble()).coerceAtLeast(1e-9)) * meanHeadwaySeconds
    val sparseTrafficGap =
        rng.nextDouble(
            from = meanHeadwaySeconds * 0.8,
            until = meanHeadwaySeconds * (2.1 - 0.5 * normalizedDensity),
        )
    val burstMix = lerp(start = 0.30, end = 0.55, fraction = normalizedDensity)

    return if (rng.nextDouble() < burstMix) {
          0.55 * exponentialGap + 0.45 * sparseTrafficGap
        } else {
          sparseTrafficGap
        }
        .coerceAtLeast(stepLength)
  }

  private fun platoonProbability(normalizedDensity: Double): Double =
      lerp(start = 0.18, end = 0.45, fraction = normalizedDensity)

  private fun samplePlatoonSize(rng: Random): Int =
      sampleWeighted(
          rng,
          listOf(
              2 to 0.50,
              3 to 0.30,
              4 to 0.15,
              5 to 0.05,
          ),
      )

  private fun lerp(start: Double, end: Double, fraction: Double): Double =
      start + (end - start) * fraction.coerceIn(0.0, 1.0)

  private fun <T> sampleWeighted(rng: Random, weightedValues: List<Pair<T, Double>>): T {
    val totalWeight = weightedValues.sumOf { (_, weight) -> weight.coerceAtLeast(0.0) }
    require(totalWeight > 0.0) { "Weighted sampling requires positive total weight." }

    val x = rng.nextDouble() * totalWeight
    var cumulative = 0.0
    for ((value, weight) in weightedValues) {
      cumulative += weight.coerceAtLeast(0.0)
      if (x <= cumulative) {
        return value
      }
    }

    return weightedValues.last().first
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
