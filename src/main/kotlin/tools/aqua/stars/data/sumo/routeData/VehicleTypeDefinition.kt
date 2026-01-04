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

package tools.aqua.stars.data.sumo.routeData

/**
 * Definition of a SUMO vehicle type (`<vType ...>`).
 *
 * Only a subset of attributes may be present in a file; for non-nullability we use sensible
 * defaults (0.0 / empty string / empty list).
 *
 * @property typeId Vehicle type id.
 * @property vehicleClass SUMO vClass (e.g., "passenger", "truck").
 * @property minGapMeters Minimum gap (m).
 * @property tauSeconds Driver reaction time / desired time headway tau (s).
 * @property parameters Custom `<param key="..." value="..."/>` entries.
 * @property rawAttributes All other XML attributes preserved for later extensions.
 */
data class VehicleTypeDefinition(
    val typeId: String,
    val vehicleClass: String,
    val minGapMeters: Double,
    val tauSeconds: Double,
    val parameters: List<TypeParameter>,
    val rawAttributes: Map<String, String>
)
