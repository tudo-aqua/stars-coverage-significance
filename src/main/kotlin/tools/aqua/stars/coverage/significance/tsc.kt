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
            optional("Has Vehicle Behind in Relevant Time Gap") {
              condition(hasVehicleBehindInRelevantTimeGap)
              leaf("Critical TTC With Vehicle Behind") {
                condition(hasCriticalTTCWithVehicleBehind)
              }
            }
            optional("Has no Vehicle in Right Lane in Relevant Time Gap") {
              condition(hasNoVehicleInRightLaneInRelevantTimeGap)
              optional("Has Vehicle in Right Lane of Right Lane in Relevant Time Gap") {
                condition(hasVehicleInRightLaneOfRightLaneInRelevantTimeGap)
                leaf("Critical TTC With Vehicle in Right Lane of Right Lane") {
                  condition(hasCriticalTTCWithVehicleInRightLaneOfRightLane)
                }
              }
            }
            optional("Has Vehicle in Right Lane in Relevant Time Gap") {
              condition(hasVehicleInRightLaneInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Right Lane") {
                condition(hasCriticalTTCWithVehicleInRightLane)
              }
            }
            optional("Has Vehicle in Front in Relevant Time Gap") {
              condition(hasVehicleInFrontInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Front") {
                condition(hasCriticalTTCWithVehicleInFront)
              }
            }
          }
          optional("Middle Lane") {
            condition(isOnMiddleLane)
            optional("Has Vehicle Behind in Relevant Time Gap") {
              condition(hasVehicleBehindInRelevantTimeGap)
              leaf("Critical TTC With Vehicle Behind") {
                condition(hasCriticalTTCWithVehicleBehind)
              }
            }
            optional("Has Vehicle in Left Lane in Relevant Time Gap") {
              condition(hasVehicleInLeftLaneInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Left Lane") {
                condition(hasCriticalTTCWithVehicleInLeftLane)
              }
            }
            optional("Has Vehicle in Right Lane in Relevant Time Gap") {
              condition(hasVehicleInRightLaneInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Right Lane") {
                condition(hasCriticalTTCWithVehicleInRightLane)
              }
            }
            optional("Has Vehicle in Front in Relevant Time Gap") {
              condition(hasVehicleInFrontInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Front") {
                condition(hasCriticalTTCWithVehicleInFront)
              }
            }
          }
          optional("Right Lane") {
            condition(isOnRightLane)
            optional("Has Vehicle Behind in Relevant Time Gap") {
              condition(hasVehicleBehindInRelevantTimeGap)
              leaf("Critical TTC With Vehicle Behind") {
                condition(hasCriticalTTCWithVehicleBehind)
              }
            }
            optional("Has Vehicle in Left Lane in Relevant Time Gap") {
              condition(hasVehicleInLeftLaneInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Left Lane") {
                condition(hasCriticalTTCWithVehicleInLeftLane)
              }
            }
            optional("Has no Vehicle in Left Lane in Relevant Time Gap") {
              condition(hasNoVehicleInLeftLaneInRelevantTimeGap)
              optional("Has Vehicle in Left Lane of Left Lane in Relevant Time Gap") {
                condition(hasVehicleInLeftLaneOfLeftLaneInRelevantTimeGap)
                leaf("Critical TTC With Vehicle in Left Lane of Left lane") {
                  condition(hasCriticalTTCWithVehicleInLeftLaneOfLeftLane)
                }
              }
            }
            optional("Has Vehicle in Front in Relevant Time Gap") {
              condition(hasVehicleInFrontInRelevantTimeGap)
              leaf("Critical TTC with Vehicle in Front") {
                condition(hasCriticalTTCWithVehicleInFront)
              }
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
