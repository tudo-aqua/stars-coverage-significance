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
 * @property sumoId Must match the SUMO vType id.
 * @property departSpeedKmh Initial departure speed in km/h (converted to m/s for SUMO exports).
 */
enum class VehicleType(val sumoId: String, val departSpeedKmh: Int) {
  /** The ego vehicle (AUT). */
  EGO("ego", 100),

  /** Passenger car with calm driving style (70 km/h at initialization). */
  CAR_CALM("car_calm", 70),

  /** Passenger car with normal driving style (100 km/h at initialization). */
  CAR_NORMAL("car_normal", 100),

  /** Passenger car with fast driving style (130 km/h at initialization). */
  CAR_SPEEDY("car_speedy", 130);

  /** Initial departure speed in m/s (converted from km/h). */
  val departSpeedMs: Double
    get() = departSpeedKmh / 3.6
}
