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

fun tsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("TTC Highway TSC") {
      all("Root") {
        monitors {
          monitor(g0Accidents.name, g0Accidents)
          monitor(g1SafeDistanceToPrecedingVehicle.name, g1SafeDistanceToPrecedingVehicle)
          monitor(g2EmergencyBraking.name, g2EmergencyBraking)
          monitor(g3MaximumSpeedLimit.name, g3MaximumSpeedLimit)
          monitor(g4TrafficFlow.name, g4TrafficFlow)
          monitor(i1Stopping.name, i1Stopping)
          monitor(i2DrivingFasterThenLeftTraffic.name, i2DrivingFasterThenLeftTraffic)
        }
        exclusive("Lane") {
          optional("Left Lane") {
            condition(isOnLeftLane)
            exclusive("Has Relevant Vehicle on Right Lane") {
              condition(hasRelevantVehicleOnRightLane)
              optional("Can Move Right") {
                condition(canMoveRight)
                leaf("Has Vehicle on Right of Right Lane") {
                  condition(hasRelevantVehicleOnRightLaneOfRightLane)
                }
              }
              leaf("Cannot Move Right") { condition(canNotMoveRight) }
            }
            optional("Has Relevant Vehicle in Front") {
              condition(hasRelevantVehicleInFront)
              leaf("Has Critical Vehicle in Front") { condition(hasCriticalVehicleInFront) }
            }
            optional("Has Relevant Vehicle in Behind") {
              condition(hasRelevantVehicleInBehind)
              leaf("Has Critical Vehicle in Behind") { condition(hasCriticalVehicleInBehind) }
            }
          }
          optional("Middle Lane") {
            condition(isOnMiddleLane)
            exclusive("Has Relevant Vehicle on Left Lane") {
              condition(hasRelevantVehicleOnLeftLane)
              optional("Can Move Left") {
                condition(canMoveLeft)
                leaf("Has Vehicle on Left of Left Lane") {
                  condition(hasRelevantVehicleOnLeftLaneOfLeftLane)
                }
              }
              leaf("Cannot Move Left") { condition(canNotMoveLeft) }
            }
            exclusive("Has Relevant Vehicle on Right Lane") {
              condition(hasRelevantVehicleOnRightLane)
              optional("Can Move Right") {
                condition(canMoveRight)
                leaf("Has Vehicle on Right of Right Lane") {
                  condition(hasRelevantVehicleOnRightLaneOfRightLane)
                }
              }
              leaf("Cannot Move Right") { condition(canNotMoveRight) }
            }
            optional("Has Relevant Vehicle in Front") {
              condition(hasRelevantVehicleInFront)
              leaf("Has Critical Vehicle in Front") { condition(hasCriticalVehicleInFront) }
            }
            optional("Has Relevant Vehicle in Behind") {
              condition(hasRelevantVehicleInBehind)
              leaf("Has Critical Vehicle in Behind") { condition(hasCriticalVehicleInBehind) }
            }
          }
          optional("Right Lane") {
            condition(isOnRightLane)
            exclusive("Has Relevant Vehicle on Left Lane") {
              condition(hasRelevantVehicleOnLeftLane)
              leaf("Cannot Move Left") { condition(canNotMoveLeft) }
              optional("Can Move Left") {
                condition(canMoveLeft)
                leaf("Has Vehicle on Left of Left Lane") {
                  condition(hasRelevantVehicleOnLeftLaneOfLeftLane)
                }
              }
            }
            optional("Has Relevant Vehicle in Front") {
              condition(hasRelevantVehicleInFront)
              leaf("Has Critical Vehicle in Front") { condition(hasCriticalVehicleInFront) }
            }
            optional("Has Relevant Vehicle in Behind") {
              condition(hasRelevantVehicleInBehind)
              leaf("Has Critical Vehicle in Behind") { condition(hasCriticalVehicleInBehind) }
            }
          }
        }
      }
    }

/** TSC for static starting configurations. */
fun oldTSC() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("Small Static TSC") {
      all("Root") {
        monitors {
          monitor(g0Accidents.name, g0Accidents)
          monitor(g1SafeDistanceToPrecedingVehicle.name, g1SafeDistanceToPrecedingVehicle)
          monitor(g3MaximumSpeedLimit.name, g3MaximumSpeedLimit)
          monitor(g4TrafficFlow.name, g4TrafficFlow)
          monitor(g2EmergencyBraking.name, g2EmergencyBraking)
          monitor(i1Stopping.name, i1Stopping)
          monitor(i2DrivingFasterThenLeftTraffic.name, i2DrivingFasterThenLeftTraffic)
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
