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

package tools.aqua.stars.coverage.significance.postEvaluation.scenario

import java.nio.file.Path
import kotlin.io.path.Path
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.EXPERIMENT_DIR
import tools.aqua.stars.coverage.significance.NETWORK_FILE_NAME
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupingMutantSplitsTable
import tools.aqua.stars.coverage.significance.db.tables.StartScenarioGroupingRunsTable
import tools.aqua.stars.coverage.significance.insertAllTSCInstance
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot
import tools.aqua.stars.coverage.significance.tsc.tsc
import tools.aqua.stars.coverage.significance.tsc.tscWithStaticDistances
import tools.aqua.stars.coverage.significance.utils.decompressAllVehiclesJson
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.dataclasses.staticData.Lane
import tools.aqua.stars.data.sumo.xml.SumoImporter

/**
 * Step B of `docs/test-bed-plan.md`: the class of every starting scenario under the project TSC
 * (`tsc()`) and the static-distance TSC (`tscWithStaticDistances()`), stored as two `kind = tsc`
 * groupings in [StartScenarioGroupingRunsTable] and [StartScenarioGroupAssignmentsTable], next to
 * the learned trees.
 *
 * Both are read off the baseline's tick 0, which is identical for every controller (`NOTES.md`).
 * The project class is the recorded `current_tsc_instance_id`. The static TSC was never part of a
 * simulation, so it is seeded into `tscs` / `tsc_instances` here and evaluated on the tick-0 scene
 * rebuilt from `all_vehicles_json_deflate`. Evaluating `tsc()` on the same rebuilt scene must give
 * the recorded class; the mismatches are printed as the check of the rebuild.
 */
object StartScenarioTscGroupings {

  /** [StartScenarioGroupingRunsTable.kind] of a TSC grouping. */
  const val KIND_TSC: String = "tsc"

  /** [StartScenarioGroupingRunsTable.kind] of a learned tree. */
  const val KIND_TREE: String = "tree"

  /** Vehicle-type file next to the network, as used by the simulation. */
  private const val V_TYPES_FILE_NAME = "vTypes.add.xml"

  private const val CHUNK_SCENARIOS = 2048

  /** Classes the plan expects at tick 0 (step B acceptance). */
  private const val EXPECTED_PROJECT_CLASSES = 68
  private const val EXPECTED_STATIC_CLASSES = 160

  /** The baseline's tick-0 row of one scenario. */
  @Suppress("UseDataClass") // A data class would compare the array by reference in equals().
  private class TickZero(val projectInstanceId: Int, val vehiclesJson: ByteArray)

  /**
   * Seeds the static TSC, assigns every scenario to its class under both TSCs, replaces their
   * earlier groupings and prints the acceptance checks.
   *
   * @param baseDir Folder with the simulation's network and vehicle-type files.
   */
  fun run(baseDir: Path = Path(EXPERIMENT_DIR)) {
    transaction {
      SchemaUtils.createMissingTablesAndColumns(
          StartScenarioGroupingRunsTable,
          StartScenarioGroupingMutantSplitsTable,
          StartScenarioGroupAssignmentsTable)
    }
    val project = tsc()
    val static = tscWithStaticDistances()
    val projectTscId = TSCsRepository.upsertAndGetId(project.toTSCEntry())
    val staticTscId = TSCsRepository.upsertAndGetId(static.toTSCEntry())
    insertAllTSCInstance(project, projectTscId)
    insertAllTSCInstance(static, staticTscId)
    val instanceIds =
        TSCInstancesRepository.getAll()
            .groupBy { it.tscId }
            .mapValues { (_, entries) ->
              entries.associate { it.instanceJson to checkNotNull(it.id) }
            }

    val network = SumoImporter.loadRoadNetwork(baseDir.resolve(NETWORK_FILE_NAME))
    // The grid network has a single edge; internal junction lanes start with ':'.
    val lanes = network.lanes.filterNot { it.laneId.startsWith(":") }.associateBy { it.laneIndex }
    val types =
        SumoImporter.parseVehicleTypesAddFile(baseDir.resolve(V_TYPES_FILE_NAME))
            .vehicleTypeById
            .mapValues { VehicleType(it.value) }

    val start = System.nanoTime()
    val ticks = readTickZero(projectTscId)
    val project0 = mutableMapOf<Int, Int>()
    val static0 = mutableMapOf<Int, Int>()
    var mismatches = 0
    for ((scenario, row) in ticks) {
      val step = timeStep(decodeSnapshots(row.vehiclesJson), lanes, types, scenario)
      project0[scenario] = row.projectInstanceId
      if (instanceIds.getValue(projectTscId)[project.evaluate(step).getJsonString()] !=
          row.projectInstanceId)
          mismatches++
      static0[scenario] =
          instanceIds.getValue(staticTscId).getValue(static.evaluate(step).getJsonString())
    }
    println("Rebuilt and evaluated ${ticks.size} tick-0 scenes in ${seconds(start)} s")

    store(projectTscId, project0)
    store(staticTscId, static0)
    println(
        "Check: ${project0.size} scenarios; project TSC ${project0.values.toSet().size} classes " +
            "(expected $EXPECTED_PROJECT_CLASSES), static TSC ${static0.values.toSet().size} classes " +
            "(expected $EXPECTED_STATIC_CLASSES); $mismatches scenarios where tsc() on the rebuilt " +
            "scene differs from the recorded class (expected 0)")
  }

  /**
   * The tick-0 scene of a scenario from its vehicle snapshots.
   *
   * @param snapshots Every vehicle at tick 0, as stored in `all_vehicles_json_deflate`.
   * @param lanes Lanes by SUMO lane index.
   * @param types Vehicle types by id; the TSC predicates do not read the type, so an unknown id
   *   falls back to any type instead of failing.
   * @param scenarioConfigId The scenario.
   */
  fun timeStep(
      snapshots: List<TickVehicleSnapshot>,
      lanes: Map<Int, Lane>,
      types: Map<String, VehicleType>,
      scenarioConfigId: Int,
  ): TimeStep {
    val vehicles =
        snapshots.map { v ->
          val lane = lanes.getValue(v.lane)
          Vehicle(
              vehicleId = v.id,
              vehicleType = types[v.type] ?: types.values.first(),
              currentLane = lane,
              currentEdge = lane.parentEdge,
              // Same semantics as the simulation: the position is the front bumper.
              positionOnLaneMeters = v.front,
              speedMetersPerSecond = v.speed,
              accelerationMetersPerSecondSquared = v.accel,
              frontBumperPositionOnLaneMeters = v.front,
              backBumperPositionOnLaneMeters = v.back,
          )
        }
    return TimeStep(
        identifier = "scenario-$scenarioConfigId-tick-0",
        runId = 0,
        sourceIdentifier = "tick-0 rebuild",
        mutantId = null,
        scenarioConfigId = scenarioConfigId,
        tickTimeMillis = 0L,
        vehiclesInTick = vehicles,
        collisionsInTick = emptyList(),
        egoManeuver = null,
        ego = vehicles[snapshots.indexOfFirst { it.ego }.also { check(it >= 0) { "No ego" } }],
    )
  }

  /** The vehicle snapshots stored in `all_vehicles_json_deflate`. */
  fun decodeSnapshots(deflated: ByteArray): List<TickVehicleSnapshot> =
      jsonConfiguration.decodeFromString(decompressAllVehiclesJson(deflated))

  /**
   * The baseline's tick-0 row per scenario. Several runs of a scenario give several rows; the first
   * is kept and disagreeing project classes are counted.
   */
  private fun readTickZero(projectTscId: Int): Map<Int, TickZero> = transaction {
    val baselineId =
        MutantsTable.select(MutantsTable.id)
            .where { MutantsTable.mutantNumber eq -1 }
            .single()[MutantsTable.id]
            .value
    val sc = ScenarioStartingConfigurationTable
    val scenarioIds = sc.select(sc.id).map { it[sc.id].value }.sorted()
    val m = MetricFailedMonitorsTable
    val rows = HashMap<Int, TickZero>(scenarioIds.size * 2)
    var duplicates = 0
    var disagreements = 0
    for (chunk in scenarioIds.chunked(CHUNK_SCENARIOS)) {
      m.select(m.startingScenarioConfiguration, m.currentTSCInstance, m.allVehiclesJsonDeflate)
          .where {
            (m.mutant eq baselineId) and
                (m.tick eq 0L) and
                (m.tsc eq projectTscId) and
                m.startingScenarioConfiguration.between(chunk.first(), chunk.last())
          }
          .forEach { r ->
            val scenario = r[m.startingScenarioConfiguration].value
            val row = TickZero(r[m.currentTSCInstance].value, r[m.allVehiclesJsonDeflate])
            val earlier = rows.putIfAbsent(scenario, row)
            if (earlier != null) {
              duplicates++
              if (earlier.projectInstanceId != row.projectInstanceId) disagreements++
            }
          }
    }
    println(
        "Baseline tick 0: ${rows.size} of ${scenarioIds.size} scenarios, $duplicates extra rows " +
            "from repeated runs, $disagreements of them with a different project class")
    rows
  }

  /** Replaces the TSC grouping of [tscId] with [groups] (scenario -> instance id). */
  private fun store(tscId: Int, groups: Map<Int, Int>) = transaction {
    val r = StartScenarioGroupingRunsTable
    r.deleteWhere { (kind eq KIND_TSC) and (r.tscId eq tscId) }
    val runId =
        r.insertAndGetId {
          it[kind] = KIND_TSC
          it[this.tscId] = tscId
          it[nGroups] = groups.values.toSet().size
        }
    StartScenarioGroupAssignmentsTable.batchInsert(
        groups.entries.sortedBy { it.key }, shouldReturnGeneratedValues = false) { (scenario, group)
          ->
          this[StartScenarioGroupAssignmentsTable.runId] = runId
          this[StartScenarioGroupAssignmentsTable.scenarioConfigId] = scenario
          this[StartScenarioGroupAssignmentsTable.groupId] = group
        }
    println("Stored TSC $tscId as grouping run ${runId.value} (${groups.size} scenarios)")
  }

  private fun seconds(startNanos: Long): String =
      "%.1f".format((System.nanoTime() - startNanos) / 1e9)
}
