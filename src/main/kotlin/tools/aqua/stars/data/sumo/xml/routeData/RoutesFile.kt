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

package tools.aqua.stars.data.sumo.xml.routeData

/**
 * In-memory representation of a SUMO routes file (`*.rou.xml`).
 *
 * @property vehicleTypes Vehicle type definitions (`<vType ...>`).
 * @property routes Route definitions (`<route ...>`).
 * @property vehicles Explicit vehicle definitions (`<vehicle ...>`).
 * @property flows Flow definitions which spawn multiple vehicles (`<flow ...>`).
 */
data class RoutesFile(
    val vehicleTypes: List<VehicleTypeDefinition>,
    val routes: List<RouteDefinition>,
    val vehicles: List<VehicleDefinition>,
    val flows: List<FlowDefinition>
) {
  /** Lookup of vehicle types by their id. */
  val vehicleTypeById: Map<String, VehicleTypeDefinition> = vehicleTypes.associateBy { it.typeId }

  /** Lookup of routes by their id. */
  val routeById: Map<String, RouteDefinition> = routes.associateBy { it.routeId }

  /** Lookup of explicit vehicles by their vehicle id. */
  val vehicleById: Map<String, VehicleDefinition> = vehicles.associateBy { it.vehicleId }

  /** Lookup of flows by their id. */
  val flowById: Map<String, FlowDefinition> = flows.associateBy { it.flowId }
}
