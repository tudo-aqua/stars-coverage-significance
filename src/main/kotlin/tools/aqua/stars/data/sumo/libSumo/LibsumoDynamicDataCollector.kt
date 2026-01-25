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
import java.util.*
import kotlin.io.path.Path
import org.eclipse.sumo.libsumo.Route
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringVector
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.coverage.significance.GRID_TRAFFIC_DIR
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.utils.getVehicleId
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.RoadNetwork
import tools.aqua.stars.data.sumo.xml.SumoImporter
import tools.aqua.stars.data.sumo.xml.importer.VehicleTypesFile

/**
 * Collector of dynamic data from a SUMO simulation using libsumo.
 *
 * @property baseDir Path to SUMO network files.
 * @property netFileName Name of SUMO network file.
 * @property vTypeAdditionalFile Name of SUMO additional file containing vehicle types.
 * @property insertionChecks SUMO insertion checks mode.
 * @property routeSteps Number of route steps per tick.
 * @property stepLength Step length in meters.
 * @property vehicleIdPrefix Prefix for vehicle ids.
 */
class LibsumoDynamicDataCollector(
    val baseDir: Path = Path(GRID_TRAFFIC_DIR),
    val netFileName: String = "grid_highway.net.xml",
    val vTypeAdditionalFile: String = "vTypes.add.xml",
    val insertionChecks: String = "none",
    val routeSteps: Int = 0,
    val stepLength: Double = 0.1,
    val vehicleIdPrefix: String = "veh",
) {
  private val netFilePath: Path = baseDir.resolve(netFileName)
  private val vTypesFile: Path = baseDir.resolve(vTypeAdditionalFile)
  private val vehicleTypesFile: VehicleTypesFile = SumoImporter.parseVehicleTypesAddFile(vTypesFile)
  private val vehicleTypesById = vehicleTypesFile.vehicleTypeById
  private val roadNetwork: RoadNetwork = SumoImporter.loadRoadNetwork(netFilePath)
  private val laneById: Map<String, Lane> = roadNetwork.laneById
  private val routeEdges: List<String> =
      roadNetwork.lanes.map { it.parentEdge.edgeId }.toSet().toList()

  /**
   * Run a generated scenario in libsumo and collect dynamic data.
   *
   * @param runId Run identifier.
   * @param scenario Generated scenario to run.
   * @param mutantId Id of the mutant which should be simulated.
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: UUID,
      scenario: GeneratedScenario,
      mutantId: UUID?
  ): List<TimeStep> =
      runGeneratedScenario(runId, scenario.toScenarioStartingConfigurationEntry(), mutantId)

  /**
   * Run a generated scenario in libsumo and collect dynamic data.
   *
   * @param runId Run identifier.
   * @param scenario Database entry of the scenario to run.
   * @param mutantId Id of the mutant which should be simulated.
   * @param onlyFirstTick Whether to only run the first tick.
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: UUID,
      scenario: ScenarioStartingConfigurationEntry,
      mutantId: UUID?,
      onlyFirstTick: Boolean = false
  ): List<TimeStep> {
    Simulation.preloadLibraries()

    // Reload simulation
    val baseArgs =
        mutableListOf(
            "--net-file",
            netFilePath.toAbsolutePath().toString(),
            "--additional-files",
            vTypesFile.toAbsolutePath().toString(),
            "--insertion-checks",
            insertionChecks,
            "--route-steps",
            routeSteps.toString(),
            "--step-length",
            stepLength.toString(),
            "--collision.action",
            "warn")
    Simulation.load(StringVector(baseArgs.toTypedArray()))

    // Add a single route for this run
    val routeId = "r_${scenario.id}"
    Route.add(routeId, StringVector(routeEdges.toTypedArray())) // route add semantics

    // Spawn vehicles from placements
    val sortedPlacements =
        scenario
            .toGeneratedScenario()
            .placements
            .sortedWith(compareBy({ it.row }, { it.lane }, { it.positionMeters }))

    var egoVehicleId: String? = null

    for (sp in sortedPlacements) {
      val vehId =
          getVehicleId(sp.type.toString(), sp.row, sp.lane, scenario.humanReadableScenarioId)
      val typeId = sp.type.sumoId
      val departLane = sp.lane.toString()
      val departPos = sp.positionMeters.toString()
      val departSpeed = sp.type.departSpeedMs.toString()

      // Add vehicle
      SumoVehicle.add(vehId, routeId, typeId, "0", departLane, departPos, departSpeed)

      if (sp.type == GridVehicleType.EGO) {
        egoVehicleId = vehId
      }
    }

    val egoId =
        egoVehicleId
            ?: run {
              error(
                  "Ego not found in placements; falling back to first spawned vehicle." +
                      "${vehicleIdPrefix}_${sortedPlacements.first().type}_${scenario.id}_r${sortedPlacements.first().row}_l${sortedPlacements.first().lane}_0")
            }

    if (mutantId != null) {
      val mutantEntry = MutantsRepository.getById(mutantId)
      checkNotNull(mutantEntry) { "Mutant with id $mutantId not found in database." }
      MutantApplier.applyToEgoVehicle(egoId, mutantEntry)
    }

    // Step until done
    val ticks = ArrayList<TimeStep>()

    if (onlyFirstTick)
        return listOfNotNull(
            getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks))

    // Run until finished
    while (Simulation.getMinExpectedNumber() > 0) {
      val timeStep =
          getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks) ?: break
      ticks += timeStep
    }

    return ticks
  }

  private fun getCurrentTimeStep(
      runId: UUID,
      scenarioConfigId: UUID?,
      egoId: String,
      mutantId: UUID?,
      scenario: ScenarioStartingConfigurationEntry,
      ticks: List<TimeStep> = emptyList()
  ): TimeStep? {
    Simulation.step()

    val simTimeSeconds = Simulation.getTime()
    val tickTimeMillis = (simTimeSeconds * 1000.0).toLong()

    val vehIds = SumoVehicle.getIDList()
    val vehiclesInTick = ArrayList<Vehicle>(vehIds.size)

    for (vehId in vehIds) {
      val laneId = SumoVehicle.getLaneID(vehId)
      val lane = laneById[laneId] ?: error("Unknown lane $laneId")

      val typeId = SumoVehicle.getTypeID(vehId)
      val rawVType = vehicleTypesById[typeId] ?: error("Unknown vehicle type $typeId")

      // vType wrapper (parsed from vTypes.add.xml)
      val vehicleType = VehicleType(rawVType)

      // Simulation-derived values
      val speedMs = SumoVehicle.getSpeed(vehId).toFloat()
      val frontPos = SumoVehicle.getLanePosition(vehId).toFloat()

      // Prefer acceleration from libsumo; fall back to finite-difference if bindings don’t expose
      // it.
      val accelMs2: Float =
          runCatching { SumoVehicle.getAcceleration(vehId).toFloat() }
              .getOrElse {
                // Fallback: a = Δv / Δt using previous tick (ticks already passed into
                // getCurrentTimeStep)
                val prev = ticks.lastOrNull()?.vehiclesInTick?.firstOrNull { it.vehicleId == vehId }

                if (prev == null) 0.0f
                else {
                  val dtSeconds = (stepLength).toFloat().coerceAtLeast(1e-6f)
                  (speedMs - prev.speedMetersPerSecond) / dtSeconds
                }
              }

      // Take SUMO default values for decel/emergency decel
      // https://sumo.dlr.de/docs/Definition_of_Vehicles%2C_Vehicle_Types%2C_and_Routes.html#available_vtype_attributes
      val decelMs2 = 4.5f
      val emergencyDecelMs2 = 9.0f

      // Get length of Vehicle for back bumper position
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
    }

    val ego = vehiclesInTick.firstOrNull { it.vehicleId == egoId }
    if (simTimeSeconds == 0.1 && ego == null) {
      error("Ego did not spawn at first tick")
    }
    if (ego == null) {
      // Early return: Ego left the simulation
      return null
    }

    val collisionsInTick = ArrayList<CollisionEvent>()
    for (collision in Simulation.getCollisions()) {
      val laneId = collision.lane ?: ""
      val lane = laneById[laneId] ?: error("Unknown lane $laneId")
      val colliderId = collision.collider ?: ""
      val victimId = collision.victim ?: ""

      val collider =
          vehiclesInTick.firstOrNull { it.vehicleId == colliderId }
              ?: error("Unknown collider $colliderId")
      val victim =
          vehiclesInTick.firstOrNull { it.vehicleId == victimId }
              ?: error("Unknown victim $victimId")

      collisionsInTick +=
          CollisionEvent(
              collisionTimeSeconds = simTimeSeconds.toFloat(),
              lane = lane,
              edge = lane.parentEdge,
              positionOnLaneMeters = collision.pos.toFloat(),
              colliderVehicle = collider,
              victimVehicle = victim,
              collisionType = collision.type ?: "",
              rawAttributes = emptyMap())
    }

    checkNotNull(scenarioConfigId)
    return TimeStep(
        runId = runId,
        identifier = "${scenario.id}#${ticks.size}",
        scenarioConfigId = scenarioConfigId,
        sourceIdentifier = scenario.humanReadableScenarioId,
        tickTimeMillis = tickTimeMillis,
        vehiclesInTick = vehiclesInTick,
        collisionsInTick = collisionsInTick,
        mutantId = mutantId,
        ego = ego)
  }
}
