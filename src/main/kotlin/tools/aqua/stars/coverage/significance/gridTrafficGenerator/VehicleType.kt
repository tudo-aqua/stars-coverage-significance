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
 * Vehicle categories used by the 3x3-grid scenario generator.
 *
 * The [sumoId] must match the configured SUMO `vType` identifiers.
 *
 * Note: the paper uses the abstract type name `ego`. If your SUMO setup does not define a dedicated
 * ego vType, set [EGO]'s [sumoId] to an existing vType (e.g., `car_normal`) and distinguish the AUT
 * via scenario metadata.
 */
enum class VehicleType(val sumoId: String) {
  /** The ego vehicle (AUT). */
  EGO("ego"),

  /** Passenger car with calm driving style (70 km/h at initialization). */
  CAR_CALM("car_calm"),

  /** Passenger car with normal driving style (100 km/h at initialization). */
  CAR_NORMAL("car_normal"),

  /** Passenger car with fast driving style (130 km/h at initialization). */
  CAR_SPEEDY("car_speedy"),
}
