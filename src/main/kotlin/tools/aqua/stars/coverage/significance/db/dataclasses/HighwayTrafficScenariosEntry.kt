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

/**
 * Data class representing a row in the HighwayTrafficScenariosTable.
 *
 * @property id The unique identifier of the entry. This is optional and can be null when inserting
 *   a new entry.
 * @property seed The seed of the scenario.
 * @property crowdiness The crowdiness of the scenario.
 * @property vehicleId The ID of the vehicle.
 * @property vehicleType The type of the vehicle.
 * @property tick The tick of the scenario.
 * @property lane The lane of the scenario.
 * @property position The position of the vehicle.
 * @property speed The speed of the vehicle.
 * @property tscInstanceId The unique identifier of the TSC instance.
 * @property createdAt The timestamp of when the entry was created.
 */
data class HighwayTrafficScenariosEntry(
    val id: Int? = null,
    val seed: Int,
    val crowdiness: Int,
    val vehicleId: String,
    val vehicleType: String,
    val tick: Long,
    val lane: Int,
    val position: Float,
    val speed: Float,
    val tscInstanceId: Int,
    val createdAt: Instant = Instant.now(),
)
