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
import tools.aqua.stars.sumo.HighwayLane
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
 * @property egoLane The lane the ego vehicle is currently on.
 * @property egoSpeedMps Ego vehicle speed (m/s).
 * @property egoAccelMps2 Ego vehicle acceleration (m/s²).
 * @property egoFrontBumperPosMeters Ego front bumper lane position (m).
 * @property egoBackBumperPosMeters Ego back bumper lane position (m).
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
 * @property surroundingFrontSpeedMps Speed of the front neighbour (m/s).
 * @property surroundingFrontFrontBumperPosMeters Front bumper position of the front neighbour (m).
 * @property surroundingFrontBackBumperPosMeters Back bumper position of the front neighbour (m).
 * @property surroundingFrontAccelMps2 Acceleration of the front neighbour (m/s²).
 * @property surroundingFrontSpeedDiffMps Speed difference to the front neighbour (m/s).
 * @property surroundingFrontAccelDiffMps2 Acceleration difference to the front neighbour (m/s²).
 * @property surroundingFrontTtcSeconds Time-to-collision to the front neighbour (s).
 * @property surroundingFrontTgSeconds Time gap to the front neighbour (s).
 * @property surroundingDistRear Distance to nearest vehicle behind on the same lane (m).
 * @property surroundingRearSpeedMps Speed of the rear neighbour (m/s).
 * @property surroundingRearFrontBumperPosMeters Front bumper position of the rear neighbour (m).
 * @property surroundingRearBackBumperPosMeters Back bumper position of the rear neighbour (m).
 * @property surroundingRearAccelMps2 Acceleration of the rear neighbour (m/s²).
 * @property surroundingRearSpeedDiffMps Speed difference to the rear neighbour (m/s).
 * @property surroundingRearAccelDiffMps2 Acceleration difference to the rear neighbour (m/s²).
 * @property surroundingRearTtcSeconds Time-to-collision to the rear neighbour (s).
 * @property surroundingRearTgSeconds Time gap to the rear neighbour (s).
 * @property surroundingDistFrontLeft Distance to nearest vehicle ahead on the left lane (m).
 * @property surroundingFrontLeftSpeedMps Speed of the front-left neighbour (m/s).
 * @property surroundingFrontLeftFrontBumperPosMeters Front bumper position of the front-left
 *   neighbour (m).
 * @property surroundingFrontLeftBackBumperPosMeters Back bumper position of the front-left
 *   neighbour (m).
 * @property surroundingFrontLeftAccelMps2 Acceleration of the front-left neighbour (m/s²).
 * @property surroundingFrontLeftSpeedDiffMps Speed difference to the front-left neighbour (m/s).
 * @property surroundingFrontLeftAccelDiffMps2 Acceleration difference to the front-left neighbour
 *   (m/s²).
 * @property surroundingFrontLeftTtcSeconds Time-to-collision to the front-left neighbour (s).
 * @property surroundingFrontLeftTgSeconds Time gap to the front-left neighbour (s).
 * @property surroundingDistFrontRight Distance to nearest vehicle ahead on the right lane (m).
 * @property surroundingFrontRightSpeedMps Speed of the front-right neighbour (m/s).
 * @property surroundingFrontRightFrontBumperPosMeters Front bumper position of the front-right
 *   neighbour (m).
 * @property surroundingFrontRightBackBumperPosMeters Back bumper position of the front-right
 *   neighbour (m).
 * @property surroundingFrontRightAccelMps2 Acceleration of the front-right neighbour (m/s²).
 * @property surroundingFrontRightSpeedDiffMps Speed difference to the front-right neighbour (m/s).
 * @property surroundingFrontRightAccelDiffMps2 Acceleration difference to the front-right neighbour
 *   (m/s²).
 * @property surroundingFrontRightTtcSeconds Time-to-collision to the front-right neighbour (s).
 * @property surroundingFrontRightTgSeconds Time gap to the front-right neighbour (s).
 * @property surroundingDistRearLeft Distance to nearest vehicle behind on the left lane (m).
 * @property surroundingRearLeftSpeedMps Speed of the rear-left neighbour (m/s).
 * @property surroundingRearLeftFrontBumperPosMeters Front bumper position of the rear-left
 *   neighbour (m).
 * @property surroundingRearLeftBackBumperPosMeters Back bumper position of the rear-left
 *   neighbour (m).
 * @property surroundingRearLeftAccelMps2 Acceleration of the rear-left neighbour (m/s²).
 * @property surroundingRearLeftSpeedDiffMps Speed difference to the rear-left neighbour (m/s).
 * @property surroundingRearLeftAccelDiffMps2 Acceleration difference to the rear-left neighbour
 *   (m/s²).
 * @property surroundingRearLeftTtcSeconds Time-to-collision to the rear-left neighbour (s).
 * @property surroundingRearLeftTgSeconds Time gap to the rear-left neighbour (s).
 * @property surroundingDistRearRight Distance to nearest vehicle behind on the right lane (m).
 * @property surroundingRearRightSpeedMps Speed of the rear-right neighbour (m/s).
 * @property surroundingRearRightFrontBumperPosMeters Front bumper position of the rear-right
 *   neighbour (m).
 * @property surroundingRearRightBackBumperPosMeters Back bumper position of the rear-right
 *   neighbour (m).
 * @property surroundingRearRightAccelMps2 Acceleration of the rear-right neighbour (m/s²).
 * @property surroundingRearRightSpeedDiffMps Speed difference to the rear-right neighbour (m/s).
 * @property surroundingRearRightAccelDiffMps2 Acceleration difference to the rear-right neighbour
 *   (m/s²).
 * @property surroundingRearRightTtcSeconds Time-to-collision to the rear-right neighbour (s).
 * @property surroundingRearRightTgSeconds Time gap to the rear-right neighbour (s).
 * @property surroundingDistLeft Distance to nearest vehicle on the left lane, any position (m).
 * @property surroundingLeftSpeedMps Speed of the left neighbour (m/s).
 * @property surroundingLeftFrontBumperPosMeters Front bumper position of the left neighbour (m).
 * @property surroundingLeftBackBumperPosMeters Back bumper position of the left neighbour (m).
 * @property surroundingLeftAccelMps2 Acceleration of the left neighbour (m/s²).
 * @property surroundingLeftSpeedDiffMps Speed difference to the left neighbour (m/s).
 * @property surroundingLeftAccelDiffMps2 Acceleration difference to the left neighbour (m/s²).
 * @property surroundingLeftTtcSeconds Time-to-collision to the left neighbour (s).
 * @property surroundingLeftTgSeconds Time gap to the left neighbour (s).
 * @property surroundingDistRight Distance to nearest vehicle on the right lane, any position (m).
 * @property surroundingRightSpeedMps Speed of the right neighbour (m/s).
 * @property surroundingRightFrontBumperPosMeters Front bumper position of the right neighbour (m).
 * @property surroundingRightBackBumperPosMeters Back bumper position of the right neighbour (m).
 * @property surroundingRightAccelMps2 Acceleration of the right neighbour (m/s²).
 * @property surroundingRightSpeedDiffMps Speed difference to the right neighbour (m/s).
 * @property surroundingRightAccelDiffMps2 Acceleration difference to the right neighbour (m/s²).
 * @property surroundingRightTtcSeconds Time-to-collision to the right neighbour (s).
 * @property surroundingRightTgSeconds Time gap to the right neighbour (s).
 * @property collisionTimeSeconds Time at which the ego-relevant collision occurred (s), null if no
 *   collision.
 * @property collisionType SUMO collision type string, null if no collision or type absent.
 * @property collisionLane Lane on which the collision occurred, null if no collision.
 * @property collisionPositionOnLaneMeters Position on lane where the collision occurred (m), null
 *   if no collision.
 * @property collisionColliderVehicleId Vehicle ID of the collider, null if no collision.
 * @property collisionColliderLane Lane of the collider vehicle at collision time, null if no
 *   collision.
 * @property collisionColliderSpeedMps Speed of the collider vehicle at collision time (m/s), null
 *   if no collision.
 * @property collisionColliderAccelMps2 Acceleration of the collider vehicle at collision time
 *   (m/s²), null if no collision.
 * @property collisionColliderFrontBumperPosMeters Front bumper position of the collider vehicle at
 *   collision time (m), null if no collision.
 * @property collisionColliderBackBumperPosMeters Back bumper position of the collider vehicle at
 *   collision time (m), null if no collision.
 * @property collisionVictimVehicleId Vehicle ID of the victim, null if no collision.
 * @property collisionVictimLane Lane of the victim vehicle at collision time, null if no collision.
 * @property collisionVictimSpeedMps Speed of the victim vehicle at collision time (m/s), null if
 *   no collision.
 * @property collisionVictimAccelMps2 Acceleration of the victim vehicle at collision time (m/s²),
 *   null if no collision.
 * @property collisionVictimFrontBumperPosMeters Front bumper position of the victim vehicle at
 *   collision time (m), null if no collision.
 * @property collisionVictimBackBumperPosMeters Back bumper position of the victim vehicle at
 *   collision time (m), null if no collision.
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
    val egoLane: HighwayLane? = null,
    val egoSpeedMps: Float? = null,
    val egoAccelMps2: Float? = null,
    val egoFrontBumperPosMeters: Float? = null,
    val egoBackBumperPosMeters: Float? = null,
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
    val surroundingFrontSpeedMps: Float? = null,
    val surroundingFrontFrontBumperPosMeters: Float? = null,
    val surroundingFrontBackBumperPosMeters: Float? = null,
    val surroundingFrontAccelMps2: Float? = null,
    val surroundingFrontSpeedDiffMps: Float? = null,
    val surroundingFrontAccelDiffMps2: Float? = null,
    val surroundingFrontTtcSeconds: Float? = null,
    val surroundingFrontTgSeconds: Float? = null,
    val surroundingDistRear: Float? = null,
    val surroundingRearSpeedMps: Float? = null,
    val surroundingRearFrontBumperPosMeters: Float? = null,
    val surroundingRearBackBumperPosMeters: Float? = null,
    val surroundingRearAccelMps2: Float? = null,
    val surroundingRearSpeedDiffMps: Float? = null,
    val surroundingRearAccelDiffMps2: Float? = null,
    val surroundingRearTtcSeconds: Float? = null,
    val surroundingRearTgSeconds: Float? = null,
    val surroundingDistFrontLeft: Float? = null,
    val surroundingFrontLeftSpeedMps: Float? = null,
    val surroundingFrontLeftFrontBumperPosMeters: Float? = null,
    val surroundingFrontLeftBackBumperPosMeters: Float? = null,
    val surroundingFrontLeftAccelMps2: Float? = null,
    val surroundingFrontLeftSpeedDiffMps: Float? = null,
    val surroundingFrontLeftAccelDiffMps2: Float? = null,
    val surroundingFrontLeftTtcSeconds: Float? = null,
    val surroundingFrontLeftTgSeconds: Float? = null,
    val surroundingDistFrontRight: Float? = null,
    val surroundingFrontRightSpeedMps: Float? = null,
    val surroundingFrontRightFrontBumperPosMeters: Float? = null,
    val surroundingFrontRightBackBumperPosMeters: Float? = null,
    val surroundingFrontRightAccelMps2: Float? = null,
    val surroundingFrontRightSpeedDiffMps: Float? = null,
    val surroundingFrontRightAccelDiffMps2: Float? = null,
    val surroundingFrontRightTtcSeconds: Float? = null,
    val surroundingFrontRightTgSeconds: Float? = null,
    val surroundingDistRearLeft: Float? = null,
    val surroundingRearLeftSpeedMps: Float? = null,
    val surroundingRearLeftFrontBumperPosMeters: Float? = null,
    val surroundingRearLeftBackBumperPosMeters: Float? = null,
    val surroundingRearLeftAccelMps2: Float? = null,
    val surroundingRearLeftSpeedDiffMps: Float? = null,
    val surroundingRearLeftAccelDiffMps2: Float? = null,
    val surroundingRearLeftTtcSeconds: Float? = null,
    val surroundingRearLeftTgSeconds: Float? = null,
    val surroundingDistRearRight: Float? = null,
    val surroundingRearRightSpeedMps: Float? = null,
    val surroundingRearRightFrontBumperPosMeters: Float? = null,
    val surroundingRearRightBackBumperPosMeters: Float? = null,
    val surroundingRearRightAccelMps2: Float? = null,
    val surroundingRearRightSpeedDiffMps: Float? = null,
    val surroundingRearRightAccelDiffMps2: Float? = null,
    val surroundingRearRightTtcSeconds: Float? = null,
    val surroundingRearRightTgSeconds: Float? = null,
    val surroundingDistLeft: Float? = null,
    val surroundingLeftSpeedMps: Float? = null,
    val surroundingLeftFrontBumperPosMeters: Float? = null,
    val surroundingLeftBackBumperPosMeters: Float? = null,
    val surroundingLeftAccelMps2: Float? = null,
    val surroundingLeftSpeedDiffMps: Float? = null,
    val surroundingLeftAccelDiffMps2: Float? = null,
    val surroundingLeftTtcSeconds: Float? = null,
    val surroundingLeftTgSeconds: Float? = null,
    val surroundingDistRight: Float? = null,
    val surroundingRightSpeedMps: Float? = null,
    val surroundingRightFrontBumperPosMeters: Float? = null,
    val surroundingRightBackBumperPosMeters: Float? = null,
    val surroundingRightAccelMps2: Float? = null,
    val surroundingRightSpeedDiffMps: Float? = null,
    val surroundingRightAccelDiffMps2: Float? = null,
    val surroundingRightTtcSeconds: Float? = null,
    val surroundingRightTgSeconds: Float? = null,
    val collisionTimeSeconds: Float? = null,
    val collisionType: String? = null,
    val collisionLane: HighwayLane? = null,
    val collisionPositionOnLaneMeters: Float? = null,
    val collisionColliderVehicleId: String? = null,
    val collisionColliderLane: HighwayLane? = null,
    val collisionColliderSpeedMps: Float? = null,
    val collisionColliderAccelMps2: Float? = null,
    val collisionColliderFrontBumperPosMeters: Float? = null,
    val collisionColliderBackBumperPosMeters: Float? = null,
    val collisionVictimVehicleId: String? = null,
    val collisionVictimLane: HighwayLane? = null,
    val collisionVictimSpeedMps: Float? = null,
    val collisionVictimAccelMps2: Float? = null,
    val collisionVictimFrontBumperPosMeters: Float? = null,
    val collisionVictimBackBumperPosMeters: Float? = null,
    val createdAt: Instant = Instant.now(),
)
