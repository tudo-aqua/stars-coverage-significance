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
import tools.aqua.stars.coverage.significance.FCD_DIR
import tools.aqua.stars.coverage.significance.FCD_REPLAY_FILE_NAME
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

  companion object {
    /**
     * Max speed (m/s) assigned to the "mutant" vType (a copy of `DEFAULT_VEHTYPE`) ego is placed
     * with. `vehicle.add`'s `departSpeed` is rejected outright if it exceeds the vType's max speed
     * — `DEFAULT_VEHTYPE`'s own built-in default isn't generous enough for every recorded ego speed
     * a lead-time replay (see [replayFromTickForDuration]) can encounter, since it now visits many
     * more ticks across a scenario's timeline than a single-step replay ever did, including
     * whatever peak speed some mutant's autopilot commanded at any point. Harmless to set high:
     * `setSpeedMode(egoId, 0)` (called right after placement) already disables SUMO's own speed
     * enforcement for ego during the actual simulation, so this only affects the one-time insertion
     * check, never the driven physics.
     */
    private const val MUTANT_MAX_SPEED_MPS = 100.0
  }

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
   * The very first returned [TimeStep] (`tickTimeMillis == 0`) is the force-placed scene itself,
   * captured before any [Simulation.step] — every subsequent [TimeStep] is captured after a step,
   * as before. This gives every scenario a real t=0 anchor (e.g. for
   * [tools.aqua.stars.coverage.significance.postEvaluation.LeadTimeReplay] to lead-time back to),
   * instead of the earliest available tick always being one step (100ms) after actual insertion.
   *
   * @param runId Run identifier.
   * @param scenario Database entry of the scenario to run.
   * @param mutantId Id of the mutant which should be simulated.
   * @param onlyFirstTick Whether to only run the first tick.
   * @param takeOnlyTicksAtXMillis If not null, only take ticks at multiples of this number of
   *   milliseconds. (e.g. if 1000, only take ticks at whole seconds).
   * @param maxLengthOfScenarioInSeconds If not null, only take ticks until this number of seconds
   *   into the scenario. (e.g. if 10, only take ticks until 10 seconds into the scenario).
   * @param writeFCDReplayFile Whether to additionally write an FCD (floating car data) trace of
   *   this run to `$FCD_DIR/$FCD_REPLAY_FILE_NAME`, for visual playback in `sumo-gui` via
   *   `sumoData/fcdReplay/fcdReplay.py` — see `manualTesting/Main.kt`.
   * @return Collected dynamic data as list of [TimeStep]s.
   */
  fun runGeneratedScenario(
      runId: Int,
      scenario: ScenarioStartingConfigurationEntry,
      mutantId: Int,
      onlyFirstTick: Boolean = false,
      takeOnlyTicksAtXMillis: Long? = TAKE_ONLY_TICKS_AT_X_MILLIS.toLong(),
      maxLengthOfScenarioInSeconds: Double? = null,
      writeFCDReplayFile: Boolean = false,
  ): List<TimeStep> {
    val routeId = "r_${scenario.id}"
    reloadSimulationWithRoute(routeId, writeFCDReplayFile)

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
                SumoVehicleType.setMaxSpeed(typeId, MUTANT_MAX_SPEED_MPS)
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

    // Captures the force-placed scene itself (t=0, before any Simulation.step()) as a TimeStep, so
    // it's recorded like every subsequent tick instead of being skipped — the loop below only ever
    // captures state *after* stepping, which used to mean the earliest tick anything (metric
    // recording, lead-time replays) could ever see was t=100ms, one step later than the vehicles'
    // actual insertion moment.
    val placementTick =
        getCurrentTimeStep(runId, scenario.id, egoId, mutantId, scenario, ticks, null)

    if (onlyFirstTick) return listOfNotNull(placementTick)

    if (placementTick != null) ticks += placementTick

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
   * A thin wrapper around [replayFromTickForDuration] with `stepCount = 1` — see that function for
   * the general multi-step case (used to give a mutant lead time before a recorded tick instead of
   * placing it right at the critical moment).
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
  ): TimeStep? =
      replayFromTickForDuration(runId, tick, scenario, mutantId, stepCount = 1).lastOrNull()

  /**
   * Reconstructs [startTick]'s full traffic scene (same placement logic as [replayTickForMutant])
   * and lets [mutantId] control the ego continuously for up to [stepCount] simulated steps —
   * re-deciding a maneuver every step, exactly like [runGeneratedScenario]'s loop — stopping early
   * if a collision ends the run or the ego leaves the simulation.
   *
   * Unlike [replayTickForMutant]'s single lookahead step, this simulates an extended window. It
   * exists to test whether giving a mutant more lead time *before* a recorded near-miss/accident
   * tick — i.e. starting the reconstruction from an earlier tick and stepping forward through to
   * (and one past) the original tick's moment — is enough for it to avoid a failure that a
   * single-step replay from the critical moment itself reports as unavoidable. A single-step SUMO
   * lane-change decision from a freshly-placed vehicle can't reproduce what a background vehicle
   * would do given a real run-up (see `G0MutantCoverageReplayAnalysis`'s "lead time" docs for the
   * full reasoning) — this makes that run-up actually happen in the replay too.
   *
   * @param runId Run identifier to tag the resulting [TimeStep]s with.
   * @param startTick The recorded tick to reconstruct and start stepping forward from — normally an
   *   earlier tick than the one actually being investigated, found by the caller.
   * @param scenario The scenario starting configuration [startTick] belongs to.
   * @param mutantId Id of the mutant which should control the ego for every step.
   * @param stepCount Number of simulation steps to run; the mutant re-decides a maneuver on each.
   * @return The resulting [TimeStep]s, one per completed step, in order. Shorter than [stepCount]
   *   if a collision ends the run early or the ego leaves the simulation; empty if the ego wasn't
   *   present immediately after placement.
   */
  fun replayFromTickForDuration(
      runId: Int,
      startTick: MetricFailedMonitorsEntry,
      scenario: ScenarioStartingConfigurationEntry,
      mutantId: Int,
      stepCount: Int,
  ): List<TimeStep> {
    val routeId = "r_replay_${startTick.id}_$stepCount"
    reloadSimulationWithRoute(routeId)

    val replayPlacements = computeReplayPlacements(startTick)
    val egoId = replayPlacements.first { it.isEgo }.vehId
    SumoVehicleType.copy("DEFAULT_VEHTYPE", "mutant")
    SumoVehicleType.setMaxSpeed("mutant", MUTANT_MAX_SPEED_MPS)

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
      return emptyList()
    }

    val mutantEntry = MutantsRepository.getById(mutantId)
    checkNotNull(mutantEntry) { "No mutant found for id=$mutantId" }
    val mutant = AutopilotMutants.create(mutantEntry.mutantNumber)

    val ticks = mutableListOf<TimeStep>()
    for (step in 1..stepCount) {
      if (!checkEgoExistence(egoId)) break

      val maneuver = mutant.controlTick(egoId)
      Simulation.step()

      val nextTick =
          getCurrentTimeStep(
              runId = runId,
              scenarioConfigId = startTick.scenarioConfigId,
              egoId = egoId,
              mutantId = mutantId,
              scenario = scenario,
              ticks = ticks,
              egoManeuver = maneuver) ?: break
      ticks += nextTick
      if (nextTick.collisionsInTick.isNotEmpty()) break
    }

    Simulation.close()
    return ticks
  }

  /**
   * Reloads a fresh simulation from the network/vType files and adds a single [routeId].
   *
   * @param writeFCDReplayFile Whether to additionally enable FCD (floating car data) trace output
   *   to `$FCD_DIR/$FCD_REPLAY_FILE_NAME` — see [runGeneratedScenario]'s matching parameter.
   */
  private fun reloadSimulationWithRoute(routeId: String, writeFCDReplayFile: Boolean = false) {
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
    if (writeFCDReplayFile) {
      baseArgs.add("--fcd-output")
      baseArgs.add(Path(FCD_DIR).toAbsolutePath().toString().plus("/$FCD_REPLAY_FILE_NAME"))
      baseArgs.add("--fcd-output.attributes")
      baseArgs.add("x,y,z,speed,acceleration")
    }

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
