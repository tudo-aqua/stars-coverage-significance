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

package tools.aqua.stars.coverage.significance.db.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.javatime.timestamp
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation.Companion.toMonitorViolations
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures
import java.util.UUID

/**
 * Table for storing the failed monitors for a mutant in a scenario starting configuration and
 * evaluation run.
 *
 * @property tsc TSC.
 * @property run Evaluation run.
 * @property startingScenarioConfiguration Scenario starting configuration.
 * @property mutant Mutant.
 * @property monitorG0Failed Whether monitor G0 failed.
 * @property monitorG1Failed Whether monitor G1 failed.
 * @property monitorG2Failed Whether monitor G2 failed.
 * @property monitorG5Failed Whether monitor G2.2 failed.
 * @property monitorG3Failed Whether monitor G3 failed.
 * @property monitorG4Failed Whether monitor G4 failed.
 * @property monitorI1Failed Whether monitor I1 failed.
 * @property monitorI2Failed Whether monitor I2 failed.
 * @property monitorI3Failed Whether monitor I3 failed.
 * @property createdAt Timestamp of creation.
 */
object MetricFailedMonitorsTable : UUIDTable("metric_failed_monitors") {
  val tsc =
      reference(
          name = "tsc_id",
          foreign = TSCsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val run =
      reference(
          name = "run_id",
          foreign = EvaluationRunsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val startingScenarioConfiguration =
      reference(
          name = "scenario_config_id",
          foreign = ScenarioStartingConfigurationTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val mutant =
      reference(
          name = "mutant_id",
          foreign = MutantsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val monitorG0Failed = bool("monitor_g0_Accidents_failed").default(false)
  val monitorG1Failed = bool("monitor_g1_SafeDistanceToPrecedingVehicle_failed").default(false)
  val monitorG2Failed = bool("monitor_g2_UnnecessaryBraking_failed").default(false)
  val monitorG3Failed = bool("monitor_g3_MaximumSpeedLimit_failed").default(false)
  val monitorG4Failed = bool("monitor_g4_TrafficFlow_failed").default(false)
  val monitorG5Failed = bool("monitor_g5_EmergencyBraking_failed").default(false)
  val monitorI1Failed = bool("monitor_i1_Stopping_failed").default(false)
  val monitorI2Failed = bool("monitor_i2_DrivingFasterThenLeftTraffic_failed").default(false)
  val monitorI3Failed = bool("monitor_i3_DangerousCutIn_failed").default(false)
  val createdAt = timestamp("created_at")

  init {
    index(true, tsc, run, startingScenarioConfiguration, mutant)

    index(false, tsc)
    index(false, run)
    index(false, startingScenarioConfiguration)
    index(false, mutant)
  }

  fun buildFailedMonitorMapping(): List<ScenarioFailure> {
    val failedMonitors = MetricFailedMonitorsTable
    val joinedWithTSCInstances =
      failedMonitors.join(
        otherTable = MetricStartingValidTSCInstancesTable,
        onColumn = startingScenarioConfiguration,
        otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
        joinType = JoinType.LEFT)

    val query =
      joinedWithTSCInstances.select(
        mutant,
        MetricStartingValidTSCInstancesTable.tscInstance,
        startingScenarioConfiguration,
        monitorG0Failed,
        monitorG1Failed,
        monitorG2Failed,
        monitorG3Failed,
        monitorG4Failed,
        monitorG5Failed,
        monitorI1Failed,
        monitorI2Failed,
        monitorI3Failed
      )

    val result = mutableMapOf<UUID, MutableMap<UUID, MutableList<MutantFailures>>>()

    for (row in query) {
      val tscInstanceId = row[MetricStartingValidTSCInstancesTable.tscInstance].value
      val scenarioInstanceId = row[startingScenarioConfiguration].value
      val mutantId = row[mutant].value
      val violations = row.toMonitorViolations()

      val scenarios = result.getOrPut(tscInstanceId) { mutableMapOf() }
      val mutants = scenarios.getOrPut(scenarioInstanceId) { mutableListOf() }

      mutants += MutantFailures(mutantId = mutantId, violations = violations)
    }

    return result.map { (tscInstanceId, scenarios) ->
      ScenarioFailure(
        scenarioId = tscInstanceId,
        scenarioInstanceFailures =
          scenarios.map { (scenarioInstanceId, mutants) ->
            ScenarioInstanceFailures(scenarioInstanceId = scenarioInstanceId, mutants = mutants)
          })
    }
  }

  fun buildFailedMutantsMapping(): List<MutantFailure> =
    join(
      otherTable = MetricStartingValidTSCInstancesTable,
      onColumn = startingScenarioConfiguration,
      otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
      joinType = JoinType.INNER)
      .select(
        MetricStartingValidTSCInstancesTable.tscInstance,
        startingScenarioConfiguration,
        mutant,
        monitorG0Failed,
        monitorG1Failed,
        monitorG2Failed,
        monitorG3Failed,
        monitorG4Failed,
        monitorG5Failed,
        monitorI1Failed,
        monitorI2Failed,
        monitorI3Failed
      )
      .mapNotNull {
        val monitorBitmask =
          (if (it[monitorG0Failed]) 1 else 0) +
              (if (it[monitorG1Failed]) 2 else 0) +
              (if (it[monitorG2Failed]) 4 else 0) +
              (if (it[monitorG3Failed]) 8 else 0) +
              (if (it[monitorG4Failed]) 16 else 0) +
              (if (it[monitorG5Failed]) 32 else 0) +
              (if (it[monitorI1Failed]) 64 else 0) +
              (if (it[monitorI2Failed]) 128 else 0) +
              (if (it[monitorI3Failed]) 256 else 0)

        MutantFailure(
          tscInstance = it[MetricStartingValidTSCInstancesTable.tscInstance].value,
          startingScenarioConfigurationID =
            it[startingScenarioConfiguration].value,
          mutantID = it[mutant].value,
          monitorBitmask = monitorBitmask)
      }
      .toList()
}
