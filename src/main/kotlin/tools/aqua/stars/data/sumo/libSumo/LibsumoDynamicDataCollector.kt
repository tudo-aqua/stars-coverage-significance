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
import kotlin.io.path.Path
import org.eclipse.sumo.libsumo.Route
import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringVector
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import org.eclipse.sumo.libsumo.VehicleType as SumoVehicleType
import tools.aqua.stars.coverage.significance.EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.TAKE_ONLY_TICKS_AT_X_MILLIS
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GeneratedScenario
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType
import tools.aqua.stars.coverage.significance.utils.getVehicleId
import tools.aqua.stars.coverage.significance.utils.indexedByKey
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.dataclasses.staticData.RoadNetwork
import tools.aqua.stars.data.sumo.xml.SumoImporter
import tools.aqua.stars.data.sumo.xml.importer.VehicleTypesFile
import tools.aqua.stars.sumo.MutantManeuver
import tools.aqua.stars.sumo.mutants.AutopilotMutants

@Suppress("LongParameterList")

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
    val baseDir: Path = Path(EXPERIMENT_DIR),
    val netFileName: String = NETWORK_FILE_NAME,
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
  fun runGeneratedScenario(runId: Int, scenario: GeneratedScenario, mutantId: Int): List<TimeStep> =
      runGeneratedScenario(runId, scenario.toScenarioStartingConfigurationEntry(), mutantId)

  /**
   * Run a generated scenario in libsumo and collect dynamic data.
   *
   * @param runId Run identifier.
   * @param scenario Database entry of the scenario to run.
   * @param mutantId Id of the mutant which should be simulated.
   * @param onlyFirstTick Whether to only run the first tick.
   * @param takeOnlyTicksAtXMillis If not null, only take ticks at multiples of this number of
   *   milliseconds. (e.g. if 1000, only take ticks at whole seconds).
   * @param maxLengthOfScenarioInSeconds If not null, only take ticks until this number of seconds
   *   into the scenario. (e.g. if 10, only take ticks until 10 seconds into the scenario).
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: Int,
      scenario: ScenarioStartingConfigurationEntry,
      mutantId: Int,
      onlyFirstTick: Boolean = false,
      takeOnlyTicksAtXMillis: Long? = TAKE_ONLY_TICKS_AT_X_MILLIS.toLong(),
      maxLengthOfScenarioInSeconds: Double? = null,
  ): List<TimeStep> {
    val routeId = "r_${scenario.id}"
    reloadSimulationWithRoute(routeId)

    // Spawn vehicles from placements
    val sortedPlacements =
        scenario
            .toGeneratedScenario()
            .placements
            .sortedWith(compareBy({ it.row }, { it.lane }, { it.positionMeters }))

    var egoVehicleId: String? = null

    val placementSpecs =
        sortedPlacements
            .indexedByKey { it.type.idLabel }
            .map { (sp, indexWithinType) ->
              val vehId = getVehicleId(sp.type.idLabel, indexWithinType)
              var typeId = sp.type.sumoId

              if (sp.type == GridVehicleType.EGO) {
                egoVehicleId = vehId
                typeId = "mutant"
                SumoVehicleType.copy("DEFAULT_VEHTYPE", typeId)
              }

              PlacementSpec(
                  vehId = vehId,
                  typeId = typeId,
                  laneIndex = sp.lane,
                  positionMeters = sp.positionMeters.toDouble(),
                  speedMps = (sp.type.departSpeedKmh - 10) / 3.6,
              )
            }

    val egoId = egoVehicleId ?: run { error("Ego not found in placements") }

    // Add all vehicles first, then force-place all of them, so that all vehicleType parameters
    // are set correctly (see PlacementSpec/addVehicles/forcePlaceVehicles KDoc).
    addVehicles(placementSpecs, routeId)
    forcePlaceVehicles(placementSpecs)

    val ticks = mutableListOf<TimeStep>()

    if (onlyFirstTick)
        return listOfNotNull(
            getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks, null))

    SumoVehicle.setSpeedMode(egoId, 0)
    SumoVehicle.setLaneChangeMode(egoId, 0)

    val mutantEntry = MutantsRepository.getById(mutantId)
    checkNotNull(mutantEntry)
    val autopilot = AutopilotMutants.create(mutantEntry.mutantNumber)

    while (Simulation.getMinExpectedNumber() > 0) {
      Simulation.step()

      if (!checkEgoExistence(egoId)) break

      val egoManeuver = autopilot.controlTick(egoId)

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

  /**
   * Reconstructs [tick]'s full traffic scene (every vehicle recorded in
   * [MetricFailedMonitorsEntry.allVehiclesJson], placed via [computeReplayPlacements] at each
   * vehicle's own recorded position/speed/ acceleration/type) in a fresh simulation, lets
   * [mutantId] take control of the ego for exactly one step, and returns the resulting next-tick
   * [TimeStep] — i.e. what that mutant would actually do faced with this exact recorded scene, and
   * what happens immediately afterwards (including collisions).
   *
   * Mirrors [runGeneratedScenario]'s loop body: one [Simulation.step] materializes the placed scene
   * as "tick T" (matching the live loop's first iteration), then the mutant's `controlTick` decides
   * a maneuver from that state, then a second [Simulation.step] applies it and produces "tick T+1",
   * which is captured via the shared [getCurrentTimeStep].
   *
   * @param runId Run identifier to tag the resulting [TimeStep] with (the run [tick] originated
   *   from).
   * @param tick The recorded tick to reconstruct.
   * @param scenario The scenario starting configuration [tick] belongs to (used only for [TimeStep]
   *   identifier/source labelling, exactly as in [runGeneratedScenario]).
   * @param mutantId Id of the mutant which should control the ego for this one step.
   * @return The resulting next-tick [TimeStep], or `null` if the ego left the simulation.
   */
  fun replayTickForMutant(
      runId: Int,
      tick: MetricFailedMonitorsEntry,
      scenario: ScenarioStartingConfigurationEntry,
      mutantId: Int,
  ): TimeStep? {
    val routeId = "r_replay_${tick.id}"
    reloadSimulationWithRoute(routeId)

    val replayPlacements = computeReplayPlacements(tick)
    val egoId = replayPlacements.first { it.isEgo }.vehId
    SumoVehicleType.copy("DEFAULT_VEHTYPE", "mutant")

    val placementSpecs =
        replayPlacements.map { rp ->
          PlacementSpec(
              vehId = rp.vehId,
              typeId = if (rp.isEgo) "mutant" else rp.vehicleType,
              laneIndex = rp.laneIndex,
              positionMeters = rp.positionMeters,
              speedMps = rp.speedMps,
          )
        }

    addVehicles(placementSpecs, routeId)
    forcePlaceVehicles(placementSpecs)

    SumoVehicle.setSpeedMode(egoId, 0)
    SumoVehicle.setLaneChangeMode(egoId, 0)

    if (!checkEgoExistence(egoId)) {
      Simulation.close()
      return null
    }

    val mutantEntry = MutantsRepository.getById(mutantId)
    checkNotNull(mutantEntry) { "No mutant found for id=$mutantId" }
    val mutant = AutopilotMutants.create(mutantEntry.mutantNumber)
    val maneuver = mutant.controlTick(egoId)

    Simulation.step()

    val nextTick =
        getCurrentTimeStep(
            runId = runId,
            scenarioConfigId = tick.scenarioConfigId,
            egoId = egoId,
            mutantId = mutantId,
            scenario = scenario,
            ticks = emptyList(),
            egoManeuver = maneuver)

    Simulation.close()
    return nextTick
  }

  /** Reloads a fresh simulation from the network/vType files and adds a single [routeId]. */
  private fun reloadSimulationWithRoute(routeId: String) {
    Simulation.preloadLibraries()

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
            "--no-warnings",
            "--collision.action",
            "warn")

    Simulation.load(StringVector(baseArgs.toTypedArray()))

    Route.add(routeId, StringVector(routeEdges.toTypedArray())) // route add semantics
  }

  /**
   * One vehicle to be added ([addVehicles]) and force-placed ([forcePlaceVehicles]).
   *
   * @property speedMps Speed to insert the vehicle at (`vehicle.add`'s `departSpeed`) and to
   *   re-apply after force-placement (see [forcePlaceVehicles]) — the single speed value actually
   *   used for this vehicle.
   */
  private data class PlacementSpec(
      val vehId: String,
      val typeId: String,
      val laneIndex: Int,
      val positionMeters: Double,
      val speedMps: Double,
  )

  /**
   * Phase 1 of vehicle placement: `vehicle.add` for every [placements] entry. Must fully complete
   * before any [forcePlaceVehicles] call — interleaving add/moveTo per vehicle instead of doing all
   * adds first was observed to leave vehicleType parameters incorrectly applied.
   */
  private fun addVehicles(placements: List<PlacementSpec>, routeId: String) {
    for (p in placements) {
      SumoVehicle.add(
          p.vehId,
          routeId,
          p.typeId,
          "0",
          p.laneIndex.toString(),
          p.positionMeters.toString(),
          p.speedMps.toString())
    }
  }

  /**
   * Phase 2 of vehicle placement: force-places every vehicle onto its exact lane/position, then
   * re-applies [PlacementSpec.speedMps]. `vehicle.moveTo` resets whatever speed `vehicle.add`'s
   * `departSpeed` established at insertion, so without this explicit `vehicle.setSpeed` call every
   * vehicle silently ends up at SUMO's own default speed instead of the requested one.
   */
  private fun forcePlaceVehicles(placements: List<PlacementSpec>) {
    for (p in placements) {
      SumoVehicle.moveTo(p.vehId, "highway_${p.laneIndex}", p.positionMeters)
      SumoVehicle.setSpeed(p.vehId, p.speedMps)
    }
  }

  private fun checkEgoExistence(egoId: String): Boolean {
    val vehIds = SumoVehicle.getIDList()
    return egoId in vehIds
  }

  private fun getCurrentTimeStep(
      runId: Int,
      scenarioConfigId: Int?,
      egoId: String,
      mutantId: Int?,
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
