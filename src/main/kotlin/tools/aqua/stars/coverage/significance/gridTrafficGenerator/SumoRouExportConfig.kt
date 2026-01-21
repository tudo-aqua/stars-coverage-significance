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
 * Configuration for SUMO .rou.xml export of generated scenarios.
 *
 * @property routeEdges List of edge types to use for routing vehicles. Default is `["highway"]`.
 * @property departTimeSeconds Departure time in seconds for vehicles. Default is `0.0`.
 * @property vehicleIdPrefix Prefix for vehicle IDs in the exported .rou.xml file. Default is
 *   `"veh"`.
 */
data class SumoRouExportConfig(
    val routeEdges: List<String> = listOf("highway"),
    val departTimeSeconds: Double = 0.0,
    val vehicleIdPrefix: String = "veh",
)
