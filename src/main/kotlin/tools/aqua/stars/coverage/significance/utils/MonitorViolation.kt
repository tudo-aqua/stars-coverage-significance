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

package tools.aqua.stars.coverage.significance.utils

import kotlin.math.pow
import org.jetbrains.exposed.sql.ResultRow
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

typealias MonitorViolationBitmask = Int

/**
 * Enum representing the different monitor violations.
 *
 * @property bit The bit position of the monitor violation in the bitmask.
 */
enum class MonitorViolation(val bit: Int) {
  G0Accidents(0),
  G1SafeDistance(1),
  G2EmergencyBraking(2),
  G3MaximumSpeedLimit(3),
  G4TrafficFlow(4),
  I1Stopping(5),
  I2FasterThanLeftTraffic(6);

  /** Holds helper functions. */
  companion object {
    /** Builds a list of all combinations of monitor violations. */
    fun buildMonitorCombinations(): MutableList<Set<MonitorViolation>> =
        entries
            .toList()
            .map { setOf(it) }
            .toMutableList()
            .apply {
              add(setOf(G0Accidents, G1SafeDistance, G4TrafficFlow, I2FasterThanLeftTraffic))
              add(
                  setOf(
                      G0Accidents,
                      G1SafeDistance,
                      G2EmergencyBraking,
                      G3MaximumSpeedLimit,
                      G4TrafficFlow,
                      I1Stopping,
                      I2FasterThanLeftTraffic))
            }

    /** Converts a result row to a list of monitor violations. */
    fun ResultRow.toMonitorViolations(): List<MonitorViolation> {
      val violations = mutableListOf<MonitorViolation>()

      if (this[MetricFailedMonitorsTable.monitorG0Failed]) violations += G0Accidents
      if (this[MetricFailedMonitorsTable.monitorG1Failed]) violations += G1SafeDistance
      if (this[MetricFailedMonitorsTable.monitorG2Failed]) violations += G2EmergencyBraking
      if (this[MetricFailedMonitorsTable.monitorG3Failed]) violations += G3MaximumSpeedLimit
      if (this[MetricFailedMonitorsTable.monitorG4Failed]) violations += G4TrafficFlow
      if (this[MetricFailedMonitorsTable.monitorI1Failed]) violations += I1Stopping
      if (this[MetricFailedMonitorsTable.monitorI2Failed]) violations += I2FasterThanLeftTraffic

      return violations
    }

    /** Converts a set of monitor violations to a bitmask. */
    fun Set<MonitorViolation>.toBitmask(): MonitorViolationBitmask =
        this.fold(0) { acc, m -> acc or 2.0.pow(m.bit.toDouble()).toInt() }

    /** Converts a bitmask to a set of monitor violations. */
    fun MonitorViolationBitmask.toSetOfMonitorViolations(): Set<MonitorViolation> =
        entries.filter { this and 2.0.pow(it.bit.toDouble()).toInt() != 0 }.toSet()

    /** Converts a [MonitorViolationBitmask] to a readable [String]. */
    fun MonitorViolationBitmask.toReadableString(): String =
        this.toSetOfMonitorViolations().joinToString(separator = ", ")
  }
}
