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
    val id: Int? = null,
    val runId: Int,
    val tscId: Int,
    val mutantId: Int,
    val scenarioConfigId: Int,
    val currentTSCInstanceId: Int,
    val lastTickTSCInstanceId: Int?,
    val previousTSCInstanceId: Int?,
    val previousTSCInstanceTick: Long?,
    val tick: Long,
    val egoManeuverSpeed: Float?,
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
    val surroundingDistFront: Float? = null,
    val surroundingDistRear: Float? = null,
    val surroundingDistFrontLeft: Float? = null,
    val surroundingDistFrontRight: Float? = null,
    val surroundingDistRearLeft: Float? = null,
    val surroundingDistRearRight: Float? = null,
    val surroundingDistLeft: Float? = null,
    val surroundingDistRight: Float? = null,
    val egoSpeedMps: Float? = null,
    val egoAccelMps2: Float? = null,
    val egoFrontBumperPosMeters: Float? = null,
    val egoBackBumperPosMeters: Float? = null,
    val surroundingFrontSpeedMps: Float? = null,
    val surroundingFrontFrontBumperPosMeters: Float? = null,
    val surroundingFrontBackBumperPosMeters: Float? = null,
    val surroundingFrontAccelMps2: Float? = null,
    val surroundingFrontSpeedDiffMps: Float? = null,
    val surroundingFrontAccelDiffMps2: Float? = null,
    val surroundingFrontTtcSeconds: Float? = null,
    val surroundingFrontTgSeconds: Float? = null,
    val surroundingRearSpeedMps: Float? = null,
    val surroundingRearFrontBumperPosMeters: Float? = null,
    val surroundingRearBackBumperPosMeters: Float? = null,
    val surroundingRearAccelMps2: Float? = null,
    val surroundingRearSpeedDiffMps: Float? = null,
    val surroundingRearAccelDiffMps2: Float? = null,
    val surroundingRearTtcSeconds: Float? = null,
    val surroundingRearTgSeconds: Float? = null,
    val surroundingFrontLeftSpeedMps: Float? = null,
    val surroundingFrontLeftFrontBumperPosMeters: Float? = null,
    val surroundingFrontLeftBackBumperPosMeters: Float? = null,
    val surroundingFrontLeftAccelMps2: Float? = null,
    val surroundingFrontLeftSpeedDiffMps: Float? = null,
    val surroundingFrontLeftAccelDiffMps2: Float? = null,
    val surroundingFrontLeftTtcSeconds: Float? = null,
    val surroundingFrontLeftTgSeconds: Float? = null,
    val surroundingFrontRightSpeedMps: Float? = null,
    val surroundingFrontRightFrontBumperPosMeters: Float? = null,
    val surroundingFrontRightBackBumperPosMeters: Float? = null,
    val surroundingFrontRightAccelMps2: Float? = null,
    val surroundingFrontRightSpeedDiffMps: Float? = null,
    val surroundingFrontRightAccelDiffMps2: Float? = null,
    val surroundingFrontRightTtcSeconds: Float? = null,
    val surroundingFrontRightTgSeconds: Float? = null,
    val surroundingRearLeftSpeedMps: Float? = null,
    val surroundingRearLeftFrontBumperPosMeters: Float? = null,
    val surroundingRearLeftBackBumperPosMeters: Float? = null,
    val surroundingRearLeftAccelMps2: Float? = null,
    val surroundingRearLeftSpeedDiffMps: Float? = null,
    val surroundingRearLeftAccelDiffMps2: Float? = null,
    val surroundingRearLeftTtcSeconds: Float? = null,
    val surroundingRearLeftTgSeconds: Float? = null,
    val surroundingRearRightSpeedMps: Float? = null,
    val surroundingRearRightFrontBumperPosMeters: Float? = null,
    val surroundingRearRightBackBumperPosMeters: Float? = null,
    val surroundingRearRightAccelMps2: Float? = null,
    val surroundingRearRightSpeedDiffMps: Float? = null,
    val surroundingRearRightAccelDiffMps2: Float? = null,
    val surroundingRearRightTtcSeconds: Float? = null,
    val surroundingRearRightTgSeconds: Float? = null,
    val surroundingLeftSpeedMps: Float? = null,
    val surroundingLeftFrontBumperPosMeters: Float? = null,
    val surroundingLeftBackBumperPosMeters: Float? = null,
    val surroundingLeftAccelMps2: Float? = null,
    val surroundingLeftSpeedDiffMps: Float? = null,
    val surroundingLeftAccelDiffMps2: Float? = null,
    val surroundingLeftTtcSeconds: Float? = null,
    val surroundingLeftTgSeconds: Float? = null,
    val surroundingRightSpeedMps: Float? = null,
    val surroundingRightFrontBumperPosMeters: Float? = null,
    val surroundingRightBackBumperPosMeters: Float? = null,
    val surroundingRightAccelMps2: Float? = null,
    val surroundingRightSpeedDiffMps: Float? = null,
    val surroundingRightAccelDiffMps2: Float? = null,
    val surroundingRightTtcSeconds: Float? = null,
    val surroundingRightTgSeconds: Float? = null,
    val createdAt: Instant = Instant.now(),
)
