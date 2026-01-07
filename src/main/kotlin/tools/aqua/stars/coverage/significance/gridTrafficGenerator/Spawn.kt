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

package tools.aqua.stars.coverage.significance.gridTrafficGenerator

/**
 * A single placed vehicle in the 3x3 grid road prefix.
 *
 * This corresponds to a tuple $(r,\ell,p,t)$ in the paper's algorithm:
 * - [row] is the longitudinal row index (0=rear, 1=middle, 2=front)
 * - [lane] is the lane index (0..2)
 * - [positionMeters] is the continuous longitudinal position sampled within the interval of the
 *   corresponding grid cell
 * - [type] is the abstract vehicle type (including [VehicleType.EGO])
 */
data class Spawn(
    val row: Int,
    val lane: Int,
    val positionMeters: Double,
    val type: VehicleType,
) {
  val cellIndex: Int
    get() = row * 3 + lane
}
