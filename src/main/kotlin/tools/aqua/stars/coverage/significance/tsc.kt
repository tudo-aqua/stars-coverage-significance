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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.tsc.builder.tsc
import tools.aqua.stars.data.sumo.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle

@SuppressWarnings("StringLiteralDuplication")
/** TSC for static starting configurations. */
fun staticTsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("Static TSC") {
      all("Root") {
        exclusive("Lane") {
          optional("Left Lane") {
            condition { isOnLeftLane.holds(it) }
            exclusive("Has Vehicle in Front Same Lane") {
              condition { hasVehicleInFrontOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition { hasVehicleBehindOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneBehindIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle on Right Lane Besides") {
              condition { hasVehicleBesidesOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneBesideIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneBesideSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneBesideIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Front on Right Lane") {
              condition { hasVehicleInFrontOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Behind on Right Lane") {
              condition { hasVehicleInBehindOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneBehindIsSlower.holds(it) } }
            }
          }
          optional("Middle Lane") {
            condition { isOnMiddleLane.holds(it) }
            exclusive("Has Vehicle in Front Same Lane") {
              condition { hasVehicleInFrontOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition { hasVehicleBehindOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneBehindIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle on Left Lane Besides") {
              condition { hasVehicleBesidesOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneBesideIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneBesideSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneBesideIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle on Right Lane Besides") {
              condition { hasVehicleBesidesOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneBesideIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneBesideSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneBesideIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Front on Left Lane") {
              condition { hasVehicleInFrontOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Front on Right Lane") {
              condition { hasVehicleInFrontOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Behind on Left Lane") {
              condition { hasVehicleInBehindOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneBehindIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Behind on Right Lane") {
              condition { hasVehicleInBehindOnRightLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnRightLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnRightLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnRightLaneBehindIsSlower.holds(it) } }
            }
          }
          optional("Right Lane") {
            condition { isOnRightLane.holds(it) }
            exclusive("Has Vehicle in Front Same Lane") {
              condition { hasVehicleInFrontOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition { hasVehicleBehindOnSameLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnSameLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnSameLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnSameLaneBehindIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle on Left Lane Besides") {
              condition { hasVehicleBesidesOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneBesideIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneBesideSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneBesideIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Front on Left Lane") {
              condition { hasVehicleInFrontOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneInFrontIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneInFrontSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneInFrontIsSlower.holds(it) } }
            }
            exclusive("Has Vehicle in Behind on Left Lane") {
              condition { hasVehicleInBehindOnLeftLane.holds(it) }
              leaf("Faster Vehicle") { condition { vehicleOnLeftLaneBehindIsFaster.holds(it) } }
              leaf("Same Speed") { condition { vehicleOnLeftLaneBehindSameSpeed.holds(it) } }
              leaf("Slower Vehicle") { condition { vehicleOnLeftLaneBehindIsSlower.holds(it) } }
            }
          }
        }
      }
    }

@SuppressWarnings("StringLiteralDuplication")
/** TSC for SUMO highway scenarios. */
fun oldTsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("SUMO Highway TSC") {
      all("Root") {
        exclusive("Traffic Density") {
          leaf("Low Traffic Density") { condition { true } }
          leaf("Medium Traffic Density") { condition { true } }
          leaf("High Traffic Density") { condition { true } }
        }
        exclusive("Lane") {
          all("Left Lane") {
            condition { true }
            optional("Road") {
              leaf("Vehicle in Front") { condition { true } }
              leaf("Vehicle Behind") { condition { true } }
              leaf("Vehicle on Right Lane") { condition { true } }
            }
            exclusive("Maneuver") {
              leaf("Follow Lane") { condition { true } }
              leaf("Change to Right Lane") { condition { true } }
            }
          }
          all("Middle Lane") {
            condition { true }
            optional("Road") {
              leaf("Vehicle in Front") { condition { true } }
              leaf("Vehicle Behind") { condition { true } }
              leaf("Vehicle on Left Lane") { condition { true } }
              leaf("Vehicle on Right Lane") { condition { true } }
            }
            exclusive("Maneuver") {
              leaf("Follow Lane") { condition { true } }
              leaf("Change to Left Lane") { condition { true } }
              leaf("Change to Right Lane") { condition { true } }
            }
          }
          all("Right Lane") {
            condition { true }
            optional("Road") {
              leaf("Vehicle in Front") { condition { true } }
              leaf("Vehicle Behind") { condition { true } }
              leaf("Vehicle on Left Lane") { condition { true } }
            }
            exclusive("Maneuver") {
              leaf("Follow Lane") { condition { true } }
              leaf("Change to Left Lane") { condition { true } }
            }
          }
        }
      }
    }
