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

package tools.aqua.stars.sumo

import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle

/** Mutant that sets the speed of the ego vehicle to 200 km/h. */
class Mutant200kmh : Mutant() {
  override fun controlTick(egoId: String): MutantManeuver {
    SumoVehicle.setSpeedMode(egoId, 0)
    SumoVehicle.setSpeed(egoId, 200.0)
    return MutantManeuver(
        newSpeedMps = 200.0, laneChangeDirection = LaneChangeDirection.NO_LANE_CHANGE)
  }
}
