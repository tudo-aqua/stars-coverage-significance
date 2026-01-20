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

package tools.aqua.stars.data.sumo.dataclasses.routeData

/**
 * SUMO flow definition (`<flow ...>`), which spawns multiple vehicles.
 *
 * SUMO generates vehicle ids typically as `flowId.N` (e.g., `flow_1.0`, `flow_1.1`, ...), which we
 * use for type inference.
 *
 * @property flowId Flow id.
 * @property beginTimeSeconds Begin time in seconds.
 * @property endTimeSeconds End time in seconds.
 * @property vehicleTypeId Referenced vehicle type id (`type="..."`).
 * @property route Route reference id (`route="..."`) or inline route.
 * @property number Number of vehicles spawned.
 * @property departSpeedSpec Depart speed specification (e.g. "max").
 */
data class FlowDefinition(
    val flowId: String,
    val beginTimeSeconds: Double,
    val endTimeSeconds: Double,
    val vehicleTypeId: String,
    val route: VehicleRouteSpecification,
    val number: Int,
    val departSpeedSpec: String
)
