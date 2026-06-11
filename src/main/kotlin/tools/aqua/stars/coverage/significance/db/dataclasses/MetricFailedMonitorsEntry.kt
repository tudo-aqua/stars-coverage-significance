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

package tools.aqua.stars.coverage.significance.db.dataclasses

import java.time.Instant
import java.util.UUID
import tools.aqua.stars.sumo.LaneChangeDirection

/**
 * Data class representing a row in the MetricFailedMonitorsTable.
 *
 * @property id Unique identifier of the metric entry.
 * @property runId Unique identifier of the evaluation run.
 * @property tscId Unique identifier of the TSC.
 * @property mutantId Unique identifier of the mutant.
 * @property scenarioConfigId Unique identifier of the scenario starting configuration.
 * @property currentTSCInstanceId Unique identifier of the TSC instance at the current tick.
 * @property lastTickTSCInstanceId Unique identifier of the TSC instance at the last tick.
 * @property previousTSCInstanceId Unique identifier of the TSC instance that changed to the current
 *   TSC instance.
 * @property previousTSCInstanceTick The tick at which the TSC instance changed to the current TSC
 *   instance.
 * @property tick The current tick.
 * @property egoManeuverSpeed The ego maneuver speed.
 * @property egoManeuverLangeChange The ego maneuver lane change.
 * @property monitorG0Failed Whether monitor G0 failed.
 * @property monitorG1Failed Whether monitor G1 failed.
 * @property monitorG2Failed Whether monitor G2 failed.
 * @property monitorG3Failed Whether monitor G3 failed.
 * @property monitorG4Failed Whether monitor G4 failed.
 * @property monitorI1Failed Whether monitor I1 failed.
 * @property monitorI2Failed Whether monitor I2 failed.
 * @property nextTickMonitorG0Failed Whether monitor G0 failed in the next tick (null = last tick).
 * @property nextTickMonitorG1Failed Whether monitor G1 failed in the next tick (null = last tick).
 * @property nextTickMonitorG2Failed Whether monitor G2 failed in the next tick (null = last tick).
 * @property nextTickMonitorG3Failed Whether monitor G3 failed in the next tick (null = last tick).
 * @property nextTickMonitorG4Failed Whether monitor G4 failed in the next tick (null = last tick).
 * @property nextTickMonitorI1Failed Whether monitor I1 failed in the next tick (null = last tick).
 * @property nextTickMonitorI2Failed Whether monitor I2 failed in the next tick (null = last tick).
 * @property surroundingDistFront Distance to nearest vehicle ahead on the same lane (m).
 * @property surroundingDistRear Distance to nearest vehicle behind on the same lane (m).
 * @property surroundingDistFrontLeft Distance to nearest vehicle ahead on the left lane (m).
 * @property surroundingDistFrontRight Distance to nearest vehicle ahead on the right lane (m).
 * @property surroundingDistRearLeft Distance to nearest vehicle behind on the left lane (m).
 * @property surroundingDistRearRight Distance to nearest vehicle behind on the right lane (m).
 * @property surroundingDistLeft Distance to nearest vehicle on the left lane, any position (m).
 * @property surroundingDistRight Distance to nearest vehicle on the right lane, any position (m).
 * @property createdAt Timestamp of when the metric entry was created.
 */
data class MetricFailedMonitorsEntry(
    val id: UUID? = null,
    val runId: UUID,
    val tscId: UUID,
    val mutantId: UUID,
    val scenarioConfigId: UUID,
    val currentTSCInstanceId: UUID,
    val lastTickTSCInstanceId: UUID?,
    val previousTSCInstanceId: UUID?,
    val previousTSCInstanceTick: Long?,
    val tick: Long,
    val egoManeuverSpeed: Double?,
    val egoManeuverLangeChange: LaneChangeDirection?,
    var monitorG0Failed: Boolean,
    var monitorG1Failed: Boolean,
    var monitorG2Failed: Boolean,
    var monitorG3Failed: Boolean,
    var monitorG4Failed: Boolean,
    var monitorI1Failed: Boolean,
    var monitorI2Failed: Boolean,
    var nextTickMonitorG0Failed: Boolean? = null,
    var nextTickMonitorG1Failed: Boolean? = null,
    var nextTickMonitorG2Failed: Boolean? = null,
    var nextTickMonitorG3Failed: Boolean? = null,
    var nextTickMonitorG4Failed: Boolean? = null,
    var nextTickMonitorI1Failed: Boolean? = null,
    var nextTickMonitorI2Failed: Boolean? = null,
    val surroundingDistFront: Double? = null,
    val surroundingDistRear: Double? = null,
    val surroundingDistFrontLeft: Double? = null,
    val surroundingDistFrontRight: Double? = null,
    val surroundingDistRearLeft: Double? = null,
    val surroundingDistRearRight: Double? = null,
    val surroundingDistLeft: Double? = null,
    val surroundingDistRight: Double? = null,
    val egoSpeedMps: Double? = null,
    val egoAccelMps2: Double? = null,
    val egoFrontBumperPosMeters: Double? = null,
    val egoBackBumperPosMeters: Double? = null,
    val surroundingFrontSpeedMps: Double? = null,
    val surroundingFrontFrontBumperPosMeters: Double? = null,
    val surroundingFrontBackBumperPosMeters: Double? = null,
    val surroundingFrontAccelMps2: Double? = null,
    val surroundingFrontSpeedDiffMps: Double? = null,
    val surroundingFrontAccelDiffMps2: Double? = null,
    val surroundingFrontTtcSeconds: Double? = null,
    val surroundingFrontTgSeconds: Double? = null,
    val surroundingRearSpeedMps: Double? = null,
    val surroundingRearFrontBumperPosMeters: Double? = null,
    val surroundingRearBackBumperPosMeters: Double? = null,
    val surroundingRearAccelMps2: Double? = null,
    val surroundingRearSpeedDiffMps: Double? = null,
    val surroundingRearAccelDiffMps2: Double? = null,
    val surroundingRearTtcSeconds: Double? = null,
    val surroundingRearTgSeconds: Double? = null,
    val surroundingFrontLeftSpeedMps: Double? = null,
    val surroundingFrontLeftFrontBumperPosMeters: Double? = null,
    val surroundingFrontLeftBackBumperPosMeters: Double? = null,
    val surroundingFrontLeftAccelMps2: Double? = null,
    val surroundingFrontLeftSpeedDiffMps: Double? = null,
    val surroundingFrontLeftAccelDiffMps2: Double? = null,
    val surroundingFrontLeftTtcSeconds: Double? = null,
    val surroundingFrontLeftTgSeconds: Double? = null,
    val surroundingFrontRightSpeedMps: Double? = null,
    val surroundingFrontRightFrontBumperPosMeters: Double? = null,
    val surroundingFrontRightBackBumperPosMeters: Double? = null,
    val surroundingFrontRightAccelMps2: Double? = null,
    val surroundingFrontRightSpeedDiffMps: Double? = null,
    val surroundingFrontRightAccelDiffMps2: Double? = null,
    val surroundingFrontRightTtcSeconds: Double? = null,
    val surroundingFrontRightTgSeconds: Double? = null,
    val surroundingRearLeftSpeedMps: Double? = null,
    val surroundingRearLeftFrontBumperPosMeters: Double? = null,
    val surroundingRearLeftBackBumperPosMeters: Double? = null,
    val surroundingRearLeftAccelMps2: Double? = null,
    val surroundingRearLeftSpeedDiffMps: Double? = null,
    val surroundingRearLeftAccelDiffMps2: Double? = null,
    val surroundingRearLeftTtcSeconds: Double? = null,
    val surroundingRearLeftTgSeconds: Double? = null,
    val surroundingRearRightSpeedMps: Double? = null,
    val surroundingRearRightFrontBumperPosMeters: Double? = null,
    val surroundingRearRightBackBumperPosMeters: Double? = null,
    val surroundingRearRightAccelMps2: Double? = null,
    val surroundingRearRightSpeedDiffMps: Double? = null,
    val surroundingRearRightAccelDiffMps2: Double? = null,
    val surroundingRearRightTtcSeconds: Double? = null,
    val surroundingRearRightTgSeconds: Double? = null,
    val surroundingLeftSpeedMps: Double? = null,
    val surroundingLeftFrontBumperPosMeters: Double? = null,
    val surroundingLeftBackBumperPosMeters: Double? = null,
    val surroundingLeftAccelMps2: Double? = null,
    val surroundingLeftSpeedDiffMps: Double? = null,
    val surroundingLeftAccelDiffMps2: Double? = null,
    val surroundingLeftTtcSeconds: Double? = null,
    val surroundingLeftTgSeconds: Double? = null,
    val surroundingRightSpeedMps: Double? = null,
    val surroundingRightFrontBumperPosMeters: Double? = null,
    val surroundingRightBackBumperPosMeters: Double? = null,
    val surroundingRightAccelMps2: Double? = null,
    val surroundingRightSpeedDiffMps: Double? = null,
    val surroundingRightAccelDiffMps2: Double? = null,
    val surroundingRightTtcSeconds: Double? = null,
    val surroundingRightTgSeconds: Double? = null,
    val createdAt: Instant = Instant.now(),
)
