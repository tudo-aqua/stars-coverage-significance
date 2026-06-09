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
    val createdAt: Instant = Instant.now(),
)
