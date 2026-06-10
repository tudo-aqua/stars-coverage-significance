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
 * Bumper-to-bumper distances from the ego vehicle to the nearest neighbour in each of the eight
 * surrounding positions, as reported by the live SUMO simulation at one tick.
 *
 * Every field is `null` when no vehicle exists in that direction within the look-ahead range.
 *
 * @property frontMeters Nearest vehicle directly ahead on the same lane.
 * @property rearMeters Nearest vehicle directly behind on the same lane.
 * @property frontLeftMeters Nearest vehicle ahead on the left adjacent lane.
 * @property frontRightMeters Nearest vehicle ahead on the right adjacent lane.
 * @property rearLeftMeters Nearest vehicle behind on the left adjacent lane.
 * @property rearRightMeters Nearest vehicle behind on the right adjacent lane.
 * @property leftMeters Nearest vehicle on the left adjacent lane (any longitudinal position).
 * @property rightMeters Nearest vehicle on the right adjacent lane (any longitudinal position).
 */
data class SurroundingVehicleDistances(
    val frontMeters: Double?,
    val rearMeters: Double?,
    val frontLeftMeters: Double?,
    val frontRightMeters: Double?,
    val rearLeftMeters: Double?,
    val rearRightMeters: Double?,
    val leftMeters: Double?,
    val rightMeters: Double?,
)
