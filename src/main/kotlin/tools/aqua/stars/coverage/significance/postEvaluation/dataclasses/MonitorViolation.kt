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

package tools.aqua.stars.coverage.significance.postEvaluation.dataclasses

import org.jetbrains.exposed.sql.ResultRow
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

enum class MonitorViolation {
  G0Accidents,
  G1SafeDistance,
  G2UnnecessaryBraking,
  G3MaximumSpeedLimit,
  G4TrafficFlow,
  G5EmergencyBraking,
  I1Stopping,
  I2FasterThanLeftTraffic,
  I3DangerousCutIn;

  companion object {
    fun buildMonitorCombinations(): MutableList<Set<MonitorViolation>> =
        MonitorViolation.entries
            .toList()
            .map { setOf(it) }
            .toMutableList()
            .apply {
              add(setOf(G0Accidents, G1SafeDistance, G4TrafficFlow, I2FasterThanLeftTraffic))
              add(
                  setOf(
                      G0Accidents,
                      G1SafeDistance,
                      G3MaximumSpeedLimit,
                      G4TrafficFlow,
                      G5EmergencyBraking,
                      I1Stopping,
                      I2FasterThanLeftTraffic,
                      I3DangerousCutIn))
            }

    fun ResultRow.toMonitorViolations(): List<MonitorViolation> {
      val violations = mutableListOf<MonitorViolation>()

      if (this[MetricFailedMonitorsTable.monitorG0Failed]) violations += G0Accidents
      if (this[MetricFailedMonitorsTable.monitorG1Failed]) violations += G1SafeDistance
      if (this[MetricFailedMonitorsTable.monitorG2Failed]) violations += G2UnnecessaryBraking
      if (this[MetricFailedMonitorsTable.monitorG3Failed]) violations += G3MaximumSpeedLimit
      if (this[MetricFailedMonitorsTable.monitorG4Failed]) violations += G4TrafficFlow
      if (this[MetricFailedMonitorsTable.monitorG5Failed]) violations += G5EmergencyBraking
      if (this[MetricFailedMonitorsTable.monitorI1Failed]) violations += I1Stopping
      if (this[MetricFailedMonitorsTable.monitorI2Failed]) violations += I2FasterThanLeftTraffic
      if (this[MetricFailedMonitorsTable.monitorI3Failed]) violations += I3DangerousCutIn

      return violations
    }
  }
}
