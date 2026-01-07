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

package tools.aqua.stars.coverage.significance.fullTrafficGenerator

/**
 * Coordinate form of a placed vehicle.
 *
 * @property type The vehicle type.
 * @property lane Lane index (0..numberOfLanes-1).
 * @property area Longitudinal area index (0..numberOfBlocksPerLane-1).
 * @property index Flat index in the mask (`lane * numberOfBlocksPerLane + area`).
 */
data class SpawnFullTraffic(
    val type: FullTrafficVehicleType,
    val lane: Int,
    val area: Int,
    val index: Int
)
