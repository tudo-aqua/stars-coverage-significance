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

package tools.aqua.stars.data.sumo.xml.importer

import tools.aqua.stars.data.sumo.xml.routeData.VehicleTypeDefinition

/**
 * In-memory representation of a SUMO additional file that only contains `<vType ...>` entries.
 *
 * @property vehicleTypes Vehicle type definitions.
 */
data class VehicleTypesFile(val vehicleTypes: List<VehicleTypeDefinition>) {
  /** Lookup of vehicle types by their id. */
  val vehicleTypeById: Map<String, VehicleTypeDefinition> = vehicleTypes.associateBy { it.typeId }
}
