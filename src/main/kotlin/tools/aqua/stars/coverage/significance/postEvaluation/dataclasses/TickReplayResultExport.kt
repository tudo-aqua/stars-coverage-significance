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

import kotlinx.serialization.Serializable

/**
 * Result of replaying one recorded tick with one mutant in control for a single simulated step (see
 * `tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector.replayTickForMutant`).
 *
 * @property tickId `metric_failed_monitors.id` of the replayed tick.
 * @property originalTick `metric_failed_monitors.tick` value of the replayed tick.
 * @property mutantId Unique identifier of the mutant that controlled the ego for this replay.
 * @property mutantNumber Mutant number (see `mutants.mutant_number`).
 * @property className Mutant class name.
 * @property maneuverSpeedMps Speed command the mutant issued this step (m/s).
 * @property maneuverLaneChangeDirection Lane-change command the mutant issued this step.
 * @property collisionOccurred Whether a collision occurred while advancing to the next tick.
 * @property collisionType SUMO collision type string, `null` if [collisionOccurred] is `false`.
 * @property nextTickEgoSpeedMps Ego speed (m/s) at the resulting next tick, `null` if the ego left
 *   the simulation.
 * @property nextTickEgoLaneIndex Ego SUMO lane index (0=right..2=left) at the next tick.
 * @property nextTickEgoFrontBumperPosMeters Ego front bumper lane position (m) at the next tick,
 *   within the reconstructed simulation (not comparable across different replayed ticks).
 * @property nextTickEgoBackBumperPosMeters Ego back bumper lane position (m) at the next tick.
 * @property nextTickSurroundingDistFront Bumper-to-bumper gap to the nearest vehicle ahead on the
 *   same lane (m) at the next tick, `null` if none.
 * @property nextTickSurroundingDistRear Bumper-to-bumper gap to the nearest vehicle behind on the
 *   same lane (m) at the next tick, `null` if none.
 * @property nextTickSurroundingDistFrontLeft Bumper-to-bumper gap to the nearest front-left
 *   neighbour (m) at the next tick, `null` if none.
 * @property nextTickSurroundingDistFrontRight Bumper-to-bumper gap to the nearest front-right
 *   neighbour (m) at the next tick, `null` if none.
 * @property nextTickSurroundingDistRearLeft Bumper-to-bumper gap to the nearest rear-left neighbour
 *   (m) at the next tick, `null` if none.
 * @property nextTickSurroundingDistRearRight Bumper-to-bumper gap to the nearest rear-right
 *   neighbour (m) at the next tick, `null` if none.
 */
@Serializable
data class TickReplayResultExport(
    val tickId: Int,
    val originalTick: Long,
    val mutantId: Int,
    val mutantNumber: Int,
    val className: String,
    val maneuverSpeedMps: Double?,
    val maneuverLaneChangeDirection: String?,
    val collisionOccurred: Boolean,
    val collisionType: String?,
    val nextTickEgoSpeedMps: Float?,
    val nextTickEgoLaneIndex: Int?,
    val nextTickEgoFrontBumperPosMeters: Float?,
    val nextTickEgoBackBumperPosMeters: Float?,
    val nextTickSurroundingDistFront: Double?,
    val nextTickSurroundingDistRear: Double?,
    val nextTickSurroundingDistFrontLeft: Double?,
    val nextTickSurroundingDistFrontRight: Double?,
    val nextTickSurroundingDistRearLeft: Double?,
    val nextTickSurroundingDistRearRight: Double?,
)
