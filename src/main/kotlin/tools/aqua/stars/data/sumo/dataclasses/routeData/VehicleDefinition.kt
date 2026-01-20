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
 * SUMO vehicle definition (`<vehicle ...>`).
 *
 * @property vehicleId Vehicle id.
 * @property vehicleTypeId Referenced vehicle type id (`type="..."`).
 * @property departTimeSeconds Depart time in seconds.
 * @property departLaneSpec Depart lane specification (e.g. "0", "best").
 * @property departSpeedSpec Depart speed specification (e.g. "max").
 * @property route Either an inline route (edges list) or a route reference id.
 * @property stops Stops attached to the vehicle.
 */
data class VehicleDefinition(
    val vehicleId: String,
    val vehicleTypeId: String,
    val departTimeSeconds: Double,
    val departLaneSpec: String,
    val departSpeedSpec: String,
    val route: VehicleRouteSpecification,
    val stops: List<StopDefinition>
)
