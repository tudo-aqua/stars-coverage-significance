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

import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable

typealias TSCInstanceId = UUID

typealias ScenarioInstanceId = UUID

typealias MutantId = UUID

enum class MonitorViolation {
  G0Accidents,
  G1SafeDistance,
  G2UnnecessaryBraking,
  G3MaximumSpeedLimit,
  G4TrafficFlow,
  G5EmergencyBraking,
  I1Stopping,
  I2FasterThanLeftTraffic,
  I3DangerousCutIn,
}

object MutantKilling {

  fun evaluate() {
    DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
    db {
      val failedMonitors = MetricFailedMonitorsTable
      val joinedWithTSCInstances =
          failedMonitors.join(
              otherTable = MetricStartingValidTSCInstancesTable,
              onColumn = MetricFailedMonitorsTable.startingScenarioConfiguration,
              otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
              joinType = JoinType.LEFT)

      val fullQuery =
          joinedWithTSCInstances.select(
              MetricFailedMonitorsTable.mutant,
              MetricStartingValidTSCInstancesTable.tscInstance,
              MetricFailedMonitorsTable.startingScenarioConfiguration,
              MetricFailedMonitorsTable.monitorG0Failed,
              MetricFailedMonitorsTable.monitorG1Failed,
              MetricFailedMonitorsTable.monitorG2Failed,
              MetricFailedMonitorsTable.monitorG3Failed,
              MetricFailedMonitorsTable.monitorG4Failed,
              MetricFailedMonitorsTable.monitorG5Failed,
              MetricFailedMonitorsTable.monitorI1Failed,
              MetricFailedMonitorsTable.monitorI2Failed,
              MetricFailedMonitorsTable.monitorI3Failed)
      val result = buildFailedMonitorMap(fullQuery)
      val s = ""
      println("Finished Loading Data from DB: ${result.size}")
    }
  }

  private fun ResultRow.toMonitorViolations(): List<MonitorViolation> {
    val violations = mutableListOf<MonitorViolation>()

    if (this[MetricFailedMonitorsTable.monitorG0Failed]) violations += MonitorViolation.G0Accidents
    if (this[MetricFailedMonitorsTable.monitorG1Failed])
        violations += MonitorViolation.G1SafeDistance
    if (this[MetricFailedMonitorsTable.monitorG2Failed])
        violations += MonitorViolation.G2UnnecessaryBraking
    if (this[MetricFailedMonitorsTable.monitorG3Failed])
        violations += MonitorViolation.G3MaximumSpeedLimit
    if (this[MetricFailedMonitorsTable.monitorG4Failed])
        violations += MonitorViolation.G4TrafficFlow
    if (this[MetricFailedMonitorsTable.monitorG5Failed])
        violations += MonitorViolation.G5EmergencyBraking
    if (this[MetricFailedMonitorsTable.monitorI1Failed]) violations += MonitorViolation.I1Stopping
    if (this[MetricFailedMonitorsTable.monitorI2Failed])
        violations += MonitorViolation.I2FasterThanLeftTraffic
    if (this[MetricFailedMonitorsTable.monitorI3Failed])
        violations += MonitorViolation.I3DangerousCutIn

    return violations
  }

  private fun buildFailedMonitorMap(
      query: Query
  ): Map<TSCInstanceId, Map<ScenarioInstanceId, Map<MutantId, List<MonitorViolation>>>> {
    val result =
        mutableMapOf<
            TSCInstanceId,
            MutableMap<ScenarioInstanceId, MutableMap<MutantId, List<MonitorViolation>>>>()

    for (row in query) {
      val tscInstance = row[MetricStartingValidTSCInstancesTable.tscInstance].value
      val scenarioConfig = row[MetricFailedMonitorsTable.startingScenarioConfiguration].value
      val mutant = row[MetricFailedMonitorsTable.mutant].value
      val violations = row.toMonitorViolations()

      val byScenarioConfig = result.getOrPut(tscInstance) { mutableMapOf() }
      val byMutant = byScenarioConfig.getOrPut(scenarioConfig) { mutableMapOf() }
      byMutant[mutant] = violations
    }

    return result
  }
}
