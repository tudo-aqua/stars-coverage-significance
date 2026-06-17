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

package tools.aqua.stars.data.sumo.dataclasses.dynamicData

/**
 * Bumper-to-bumper distances, speed, lane position, and acceleration from the ego vehicle to the
 * nearest neighbour in each of the six surrounding cells (front, rear, front-left, front-right,
 * rear-left, rear-right), as reported by the live SUMO simulation at one tick.
 *
 * Every field group is `null` when no vehicle exists in that cell. The speed/position/accel fields
 * carry the state of whichever vehicle produced the distance value.
 *
 * Cell assignment for adjacent-lane vehicles:
 * - `gapAhead ≥ 0` (vehicle rear at or ahead of ego front) → **front-left** / **front-right**;
 *   distance = `gapAhead` (0 when touching).
 * - `gapBehind ≥ 0` (vehicle front at or behind ego rear) → **rear-left** / **rear-right**;
 *   distance = `gapBehind` (0 when touching).
 * - Both negative (longitudinal overlap, vehicle directly beside ego) → front or rear cell based on
 *   which gap is closer to zero; distance = 0.
 *
 * @property frontMeters Bumper-to-bumper gap to the nearest vehicle fully ahead on the same lane
 *   (m).
 * @property frontSpeedMps Speed of the front neighbour (m/s).
 * @property frontFrontBumperPositionMeters Front-bumper lane position of the front neighbour (m).
 * @property frontBackBumperPositionMeters Back-bumper lane position of the front neighbour (m).
 * @property frontAccelMps2 Acceleration of the front neighbour (m/s²).
 * @property rearMeters Bumper-to-bumper gap to the nearest vehicle fully behind on the same lane
 *   (m).
 * @property rearSpeedMps Speed of the rear neighbour (m/s).
 * @property rearFrontBumperPositionMeters Front-bumper lane position of the rear neighbour (m).
 * @property rearBackBumperPositionMeters Back-bumper lane position of the rear neighbour (m).
 * @property rearAccelMps2 Acceleration of the rear neighbour (m/s²).
 * @property frontLeftMeters Bumper-to-bumper gap to the nearest vehicle whose rear bumper is at or
 *   ahead of the ego's front bumper on the left lane (m; 0 when touching).
 * @property frontLeftSpeedMps Speed of the front-left neighbour (m/s).
 * @property frontLeftFrontBumperPositionMeters Front-bumper lane position of the front-left
 *   neighbour (m).
 * @property frontLeftBackBumperPositionMeters Back-bumper lane position of the front-left neighbour
 *   (m).
 * @property frontLeftAccelMps2 Acceleration of the front-left neighbour (m/s²).
 * @property frontRightMeters Bumper-to-bumper gap to the nearest vehicle whose rear bumper is at or
 *   ahead of the ego's front bumper on the right lane (m; 0 when touching).
 * @property frontRightSpeedMps Speed of the front-right neighbour (m/s).
 * @property frontRightFrontBumperPositionMeters Front-bumper lane position of the front-right
 *   neighbour (m).
 * @property frontRightBackBumperPositionMeters Back-bumper lane position of the front-right
 *   neighbour (m).
 * @property frontRightAccelMps2 Acceleration of the front-right neighbour (m/s²).
 * @property rearLeftMeters Bumper-to-bumper gap to the nearest vehicle whose front bumper is at or
 *   behind the ego's rear bumper on the left lane (m; 0 when touching).
 * @property rearLeftSpeedMps Speed of the rear-left neighbour (m/s).
 * @property rearLeftFrontBumperPositionMeters Front-bumper lane position of the rear-left neighbour
 *   (m).
 * @property rearLeftBackBumperPositionMeters Back-bumper lane position of the rear-left neighbour
 *   (m).
 * @property rearLeftAccelMps2 Acceleration of the rear-left neighbour (m/s²).
 * @property rearRightMeters Bumper-to-bumper gap to the nearest vehicle whose front bumper is at or
 *   behind the ego's rear bumper on the right lane (m; 0 when touching).
 * @property rearRightSpeedMps Speed of the rear-right neighbour (m/s).
 * @property rearRightFrontBumperPositionMeters Front-bumper lane position of the rear-right
 *   neighbour (m).
 * @property rearRightBackBumperPositionMeters Back-bumper lane position of the rear-right neighbour
 *   (m).
 * @property rearRightAccelMps2 Acceleration of the rear-right neighbour (m/s²).
 */
data class SurroundingVehicleDistances(
    val frontMeters: Double?,
    val rearMeters: Double?,
    val frontLeftMeters: Double?,
    val frontRightMeters: Double?,
    val rearLeftMeters: Double?,
    val rearRightMeters: Double?,
    val frontSpeedMps: Double? = null,
    val frontFrontBumperPositionMeters: Double? = null,
    val frontBackBumperPositionMeters: Double? = null,
    val frontAccelMps2: Double? = null,
    val rearSpeedMps: Double? = null,
    val rearFrontBumperPositionMeters: Double? = null,
    val rearBackBumperPositionMeters: Double? = null,
    val rearAccelMps2: Double? = null,
    val frontLeftSpeedMps: Double? = null,
    val frontLeftFrontBumperPositionMeters: Double? = null,
    val frontLeftBackBumperPositionMeters: Double? = null,
    val frontLeftAccelMps2: Double? = null,
    val frontRightSpeedMps: Double? = null,
    val frontRightFrontBumperPositionMeters: Double? = null,
    val frontRightBackBumperPositionMeters: Double? = null,
    val frontRightAccelMps2: Double? = null,
    val rearLeftSpeedMps: Double? = null,
    val rearLeftFrontBumperPositionMeters: Double? = null,
    val rearLeftBackBumperPositionMeters: Double? = null,
    val rearLeftAccelMps2: Double? = null,
    val rearRightSpeedMps: Double? = null,
    val rearRightFrontBumperPositionMeters: Double? = null,
    val rearRightBackBumperPositionMeters: Double? = null,
    val rearRightAccelMps2: Double? = null,
)
