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

@file:Suppress("StringLiteralDuplication")

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.tsc.builder.tsc
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/** TSC for static starting configurations. */
fun staticTsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("Static TSC") {
      all("Root") {
        monitors {
          monitor(g0Accidents.name, g0Accidents)
          monitor(g1SafeDistanceToPrecedingVehicle.name, g1SafeDistanceToPrecedingVehicle)
          monitor(g2UnnecessaryBraking.name, g2UnnecessaryBraking)
          monitor(g3MaximumSpeedLimit.name, g3MaximumSpeedLimit)
          monitor(g4TrafficFlow.name, g4TrafficFlow)
          monitor(g5AbruptBraking.name, g5AbruptBraking)
          monitor(i1Stopping.name, i1Stopping)
          monitor(i2DrivingFasterThenLeftTraffic.name, i2DrivingFasterThenLeftTraffic)
          monitor(i3DangerousCutIn.name, i3DangerousCutIn)
        }
        exclusive("Lane") {
          optional("Left Lane") {
            condition(isOnLeftLane)
            exclusive("Has Vehicle in Front Same Lane") {
              condition(hasVehicleInFrontOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition(hasVehicleBehindOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneBehindIsSlower) }
            }
            exclusive("Has Vehicle on Right Lane Besides") {
              condition(hasVehicleBesidesOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneBesideIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneBesideSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneBesideIsSlower) }
            }
            exclusive("Has Vehicle in Front on Right Lane") {
              condition(hasVehicleInFrontOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle in Behind on Right Lane") {
              condition(hasVehicleInBehindOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneBehindIsSlower) }
            }
          }
          optional("Middle Lane") {
            condition(isOnMiddleLane)
            exclusive("Has Vehicle in Front Same Lane") {
              condition(hasVehicleInFrontOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition(hasVehicleBehindOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneBehindIsSlower) }
            }
            exclusive("Has Vehicle on Left Lane Besides") {
              condition(hasVehicleBesidesOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneBesideIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneBesideSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneBesideIsSlower) }
            }
            exclusive("Has Vehicle on Right Lane Besides") {
              condition(hasVehicleBesidesOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneBesideIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneBesideSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneBesideIsSlower) }
            }
            exclusive("Has Vehicle in Front on Left Lane") {
              condition(hasVehicleInFrontOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle in Front on Right Lane") {
              condition(hasVehicleInFrontOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle in Behind on Left Lane") {
              condition(hasVehicleInBehindOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneBehindIsSlower) }
            }
            exclusive("Has Vehicle in Behind on Right Lane") {
              condition(hasVehicleInBehindOnRightLane)
              leaf("Faster Vehicle") { condition(vehicleOnRightLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnRightLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnRightLaneBehindIsSlower) }
            }
          }
          optional("Right Lane") {
            condition(isOnRightLane)
            exclusive("Has Vehicle in Front Same Lane") {
              condition(hasVehicleInFrontOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle Behind Same Lane") {
              condition(hasVehicleBehindOnSameLane)
              leaf("Faster Vehicle") { condition(vehicleOnSameLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnSameLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnSameLaneBehindIsSlower) }
            }
            exclusive("Has Vehicle on Left Lane Besides") {
              condition(hasVehicleBesidesOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneBesideIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneBesideSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneBesideIsSlower) }
            }
            exclusive("Has Vehicle in Front on Left Lane") {
              condition(hasVehicleInFrontOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneInFrontIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneInFrontSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneInFrontIsSlower) }
            }
            exclusive("Has Vehicle in Behind on Left Lane") {
              condition(hasVehicleInBehindOnLeftLane)
              leaf("Faster Vehicle") { condition(vehicleOnLeftLaneBehindIsFaster) }
              leaf("Same Speed") { condition(vehicleOnLeftLaneBehindSameSpeed) }
              leaf("Slower Vehicle") { condition(vehicleOnLeftLaneBehindIsSlower) }
            }
          }
        }
      }
    }

//noinspection DuplicatedCode
/** TSC for static starting configurations. */
fun smallStaticTsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("Small Static TSC") {
      all("Root") {
        monitors {
          monitor(g0Accidents.name, g0Accidents)
          monitor(g1SafeDistanceToPrecedingVehicle.name, g1SafeDistanceToPrecedingVehicle)
          monitor(g2UnnecessaryBraking.name, g2UnnecessaryBraking)
          monitor(g3MaximumSpeedLimit.name, g3MaximumSpeedLimit)
          monitor(g4TrafficFlow.name, g4TrafficFlow)
          monitor(g5AbruptBraking.name, g5AbruptBraking)
          monitor(i1Stopping.name, i1Stopping)
          monitor(i2DrivingFasterThenLeftTraffic.name, i2DrivingFasterThenLeftTraffic)
          monitor(i3DangerousCutIn.name, i3DangerousCutIn)
        }
        exclusive("Lane") {
          optional("Left Lane") {
            condition(isOnLeftLane)
            leaf("Has Slower Vehicle in Front Same Lane") {
              condition(vehicleOnSameLaneInFrontIsSlower)
            }
            leaf("Has Vehicle on Right Lane Besides") { condition(hasVehicleBesidesOnRightLane) }
            leaf("Has Slower Vehicle in Front on Right Lane") {
              condition(vehicleOnRightLaneInFrontIsSlower)
            }
            leaf("Has Faster Vehicle in Behind on Right Lane") {
              condition(vehicleOnRightLaneBehindIsFaster)
            }
          }
          optional("Middle Lane") {
            condition(isOnMiddleLane)
            leaf("Has Slower Vehicle in Front Same Lane") {
              condition(vehicleOnSameLaneInFrontIsSlower)
            }
            leaf("Has Vehicle on Left Lane Besides") { condition(hasVehicleBesidesOnLeftLane) }
            leaf("Has Vehicle on Right Lane Besides") { condition(hasVehicleBesidesOnRightLane) }
            leaf("Has Slower Vehicle in Front on Left Lane") {
              condition(vehicleOnLeftLaneInFrontIsSlower)
            }
            leaf("Has Slower Vehicle in Front on Right Lane") {
              condition(vehicleOnRightLaneInFrontIsSlower)
            }
            leaf("Has Faster Vehicle in Behind on Left Lane") {
              condition(vehicleOnLeftLaneBehindIsFaster)
            }
            leaf("Has Faster Vehicle in Behind on Right Lane") {
              condition(vehicleOnRightLaneBehindIsFaster)
            }
          }
          optional("Right Lane") {
            condition(isOnRightLane)
            leaf("Has Slower Vehicle in Front Same Lane") {
              condition(vehicleOnSameLaneInFrontIsSlower)
            }
            leaf("Has Vehicle on Left Lane Besides") { condition(hasVehicleBesidesOnLeftLane) }
            leaf("Has Slower Vehicle in Front on Left Lane") {
              condition(vehicleOnLeftLaneInFrontIsSlower)
            }
            leaf("Has Faster Vehicle in Behind on Left Lane") {
              condition(vehicleOnLeftLaneBehindIsFaster)
            }
          }
        }
      }
    }

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
