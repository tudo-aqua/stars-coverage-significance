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

package tools.aqua.stars.coverage.significance.manualTesting

import java.nio.file.Path
import java.util.ArrayList
import java.util.UUID
import kotlin.io.path.Path
import org.eclipse.sumo.libsumo.Route
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringVector
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import org.eclipse.sumo.libsumo.VehicleType as SumoVehicleType
import tools.aqua.stars.coverage.significance.FCD_DIR
import tools.aqua.stars.coverage.significance.FCD_REPLAY_FILE_NAME
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.TAKE_ONLY_TICKS_AT_X_MILLIS
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.utils.getVehicleId
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.RoadNetwork
import tools.aqua.stars.data.sumo.libSumo.sampleEgoSurroundingDistances
import tools.aqua.stars.data.sumo.xml.SumoImporter
import tools.aqua.stars.data.sumo.xml.importer.VehicleTypesFile
import tools.aqua.stars.sumo.Mutant
import tools.aqua.stars.sumo.MutantManeuver

/**
 * Collector of dynamic data from a SUMO simulation using libsumo.
 *
 * @property baseDir Path to SUMO network files.
 * @property netFileName Name of SUMO network file.
 * @property vTypeAdditionalFile Name of SUMO additional file containing vehicle types.
 * @property insertionChecks SUMO insertion checks mode.
 * @property routeSteps Number of route steps per tick.
 * @property stepLength Length of a single simulation step in seconds.
 * @property vehicleIdPrefix Prefix for vehicle IDs.
 */
class LibsumoMutantDataCollector(
    val baseDir: Path = Path(FCD_DIR),
    val netFileName: String = NETWORK_FILE_NAME,
    val vTypeAdditionalFile: String = "fcdReplay.add.xml",
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
   * @param mutant The mutant which should be simulated.
   * @param mutantId Id of the mutant which should be simulated.
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: UUID,
      scenario: GeneratedScenario,
      mutant: Mutant,
      mutantId: UUID
  ): List<TimeStep> =
      runGeneratedScenario(runId, scenario.toScenarioStartingConfigurationEntry(), mutant, mutantId)

  /**
   * Run a generated scenario in libsumo and collect dynamic data.
   *
   * @param runId Run identifier.
   * @param scenario Database entry of the scenario to run.
   * @param mutant The mutant which should be simulated.
   * @param mutantId Id of the mutant which should be simulated.
   * @param onlyFirstTick Whether to only run the first tick.
   * @param takeOnlyTicksAtXMillis If not null, only take ticks at multiples of this number of
   *   milliseconds. (e.g. if 1000, only take ticks at whole seconds).
   * @param maxLengthOfScenarioInSeconds If not null, only take ticks until this number of seconds
   *   into the scenario. (e.g. if 10, only take ticks until 10 seconds into the scenario).
   * @param writeFCDReplayFile Whether to write an FCD replay file for the scenario.
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: UUID,
      scenario: ScenarioStartingConfigurationEntry,
      mutant: Mutant,
      mutantId: UUID,
      onlyFirstTick: Boolean = false,
      takeOnlyTicksAtXMillis: Long? = TAKE_ONLY_TICKS_AT_X_MILLIS.toLong(),
      maxLengthOfScenarioInSeconds: Double? = null,
      writeFCDReplayFile: Boolean = false
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
            "none",
            "--route-steps",
            "0.0",
            "--step-length",
            "0.1",
            "--seed",
            "1",
            "--collision.action",
            "warn")
    if (writeFCDReplayFile) {
      baseArgs.add("--fcd-output")
      baseArgs.add(Path(FCD_DIR).toAbsolutePath().toString().plus("/$FCD_REPLAY_FILE_NAME"))
      baseArgs.add("--fcd-output.attributes")
      baseArgs.add("x,y,z,speed,acceleration")
    }

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
      var typeId = sp.type.sumoId
      val departLane = sp.lane.toString()
      val departPos = sp.positionMeters.toString()
      val departSpeed = ((sp.type.departSpeedKmh - 10) / 3.6).toString()

      if (sp.type == GridVehicleType.EGO) {
        egoVehicleId = vehId
        typeId = "mutant"
        SumoVehicleType.copy("DEFAULT_VEHTYPE", typeId)
      }

      // Add vehicle
      SumoVehicle.add(vehId, routeId, typeId, "0", departLane, departPos, departSpeed)
    }

    val egoId = egoVehicleId ?: run { error("Ego not found in placements") }

    // Force placement of vehicle into simulation, so that all vehicleType parameters are set
    // correctly
    sortedPlacements.forEach { placement ->
      val vehId =
          getVehicleId(
              placement.type.toString(),
              placement.row,
              placement.lane,
              scenario.humanReadableScenarioId)
      val departLane = placement.lane.toString()
      val departPos = placement.positionMeters

      // Force place vehicle
      SumoVehicle.moveTo(vehId, "highway_$departLane", departPos.toDouble())
    }

    val ticks = mutableListOf<TimeStep>()

    if (onlyFirstTick)
        return listOfNotNull(
            getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks, null))

    SumoVehicle.setSpeedMode(egoId, 0)
    SumoVehicle.setLaneChangeMode(egoId, 0)

    while (Simulation.getMinExpectedNumber() > 0) {
      Simulation.step()

      if (!checkEgoExistence(egoId)) break

      val egoManeuver = mutant.controlTick(egoId)

      val timeStep =
          getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks, egoManeuver)
              ?: break
      ticks += timeStep
      if (timeStep.collisionsInTick.isNotEmpty()) break
    }

    Simulation.close()

    var resultList: List<TimeStep> = ticks
    if (takeOnlyTicksAtXMillis != null) {
      resultList = ticks.filter { tick -> tick.tickTimeMillis % takeOnlyTicksAtXMillis == 0L }
    }
    if (maxLengthOfScenarioInSeconds != null) {
      resultList =
          resultList.takeWhile { tick ->
            tick.tickTimeMillis < (maxLengthOfScenarioInSeconds * 1_000L)
          }
    }

    return resultList
  }

  private fun checkEgoExistence(egoId: String): Boolean {
    val vehIds = SumoVehicle.getIDList()
    return egoId in vehIds
  }

  private fun getCurrentTimeStep(
      runId: UUID,
      scenarioConfigId: UUID?,
      egoId: String,
      mutantId: UUID,
      scenario: ScenarioStartingConfigurationEntry,
      ticks: List<TimeStep> = emptyList(),
      egoManeuver: MutantManeuver?
  ): TimeStep? {
    val simTimeSeconds = Simulation.getTime()
    val tickTimeMillis = (simTimeSeconds * 1000.0).toLong()

    val vehIds = SumoVehicle.getIDList()
    val vehiclesInTick = ArrayList<Vehicle>(vehIds.size)

    for (vehId in vehIds) {
      val laneId = SumoVehicle.getLaneID(vehId)
      val lane = laneById[laneId] ?: error("Unknown lane $laneId")

      val typeId = SumoVehicle.getTypeID(vehId)
      val rawVType =
          if (typeId.lowercase().contains("mutant")) {
            vehicleTypesById["ego"] ?: error("Unknown vehicle type $typeId")
          } else {
            vehicleTypesById[typeId] ?: error("Unknown vehicle type $typeId")
          }

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

    val surroundingDistances = sampleEgoSurroundingDistances(egoId)

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
        ego = ego,
        egoManeuver = egoManeuver,
        egoSurroundingVehicleDistances = surroundingDistances)
  }
}
