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

import tools.aqua.stars.core.evaluation.Predicate.Companion.predicate
import tools.aqua.stars.core.evaluation.VariablePredicate.Companion.predicate
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.Interval
import tools.aqua.stars.logic.kcmftbl.future.globally
import tools.aqua.stars.logic.kcmftbl.past.once
import tools.aqua.stars.logic.kcmftbl.past.previous

/** Infix function for logical implication. */
infix fun Boolean.implies(other: Boolean): Boolean = !this || other

/** Predicate for checking whether two vehicles collide with each other. */
val collidesWith =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Collides With") { tick, (ego, otherVehicle) ->
      tick.collisionsInTick.any { collision ->
        (collision.colliderVehicle == ego && collision.victimVehicle == otherVehicle) ||
            (collision.colliderVehicle == otherVehicle && collision.victimVehicle == ego)
      }
    }

/** General Traffic Rules: G_0 Accidents - Predicate implementation. */
val g0Accidents =
    predicate<TimeStep>("G_0 Accidents") { startingTick ->
      globally(startingTick) { tick ->
        tick.nonEgoVehicles.all { otherVehicle ->
          !collidesWith.holds(tick, tick.ego to otherVehicle)
        }
      }
    }

val cutIn = predicate<TimeStep, Pair<Vehicle, Vehicle>>("Cut In") { _, (ego, otherVehicle) -> true }

val keepSafeDistancePreceding =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Keep Safe Distance Preceding") {
        _,
        (ego, otherVehicle) ->
      true
    }

/**
 * A recent cut-in (identified as a cut-in start within the last tCutIn seconds) temporarily relaxes
 * the requirement G_1.
 */
val tCutIn: Long = 10L

val g1SafeDistanceToPrecedingVehicle =
    predicate<TimeStep>("G_1 Safe Distance To Preceding Vehicle") { startingTick ->
      globally(startingTick) { tick ->
        tick.nonEgoVehicles.all { otherVehicle ->
          (isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego) &&
              !once(
                  tick,
                  interval =
                      Interval(
                          TickDifferenceMilliseconds(0L), TickDifferenceMilliseconds(tCutIn))) {
                      onceTick ->
                    cutIn.holds(
                        onceTick,
                        onceTick.getVehicleById(otherVehicle.vehicleId) to onceTick.ego) &&
                        previous(onceTick) { previousTick ->
                          !cutIn.holds(
                              previousTick,
                              previousTick.getVehicleById(otherVehicle.vehicleId) to
                                  previousTick.ego)
                        }
                  }) implies keepSafeDistancePreceding.holds(tick, tick.ego to otherVehicle)
        }
      }
    }

val unnecessaryBraking = predicate<TimeStep>("Unnecessary Braking") { true }

val g2UnnecessaryBraking =
    predicate<TimeStep>("G_2 Unnecessary Braking") { tick ->
      globally(tick) { globallyTick -> !unnecessaryBraking.holds(globallyTick) }
    }

val keepLaneSpeedLimit = predicate<TimeStep>("Keep Lane Speed Limit") { true }

val keepFovSpeedLimit = predicate<TimeStep>("Keep FOV Speed Limit") { true }

val keepBrakingSpeedLimit = predicate<TimeStep>("Keep Braking Speed Limit") { true }

val g3MaximumSpeedLimit =
    predicate<TimeStep>("G_3 Maximum Speed Limit") { tick ->
      globally(tick) { globallyTick ->
        keepLaneSpeedLimit.holds(globallyTick) &&
            keepFovSpeedLimit.holds(globallyTick) &&
            keepBrakingSpeedLimit.holds(globallyTick)
      }
    }

val slowLeadingVehicle = predicate<TimeStep>("Slow Leading Vehicle") { true }

val preservesFlow = predicate<TimeStep>("Preserves Flow") { true }

val g4TrafficFlow =
    predicate<TimeStep>("G_4 Traffic Flow") { tick ->
      globally(tick) { globallyTick ->
        !slowLeadingVehicle.holds(globallyTick) implies preservesFlow.holds(globallyTick)
      }
    }

val inCongestion =
    predicate<TimeStep, Pair<Vehicle, List<Vehicle>>>("In Congestion") { tick, _ -> true }

val existStandingLeadingVehicle = predicate<TimeStep>("Exist Standing Leading Vehicle") { true }

val inStandStill = predicate<TimeStep>("In Stand Still") { true }

val i1Stopping =
    predicate<TimeStep>("I_1 Stopping") { tick ->
      globally(tick) { globallyTick ->
        !(inCongestion.holds(globallyTick, globallyTick.ego to globallyTick.nonEgoVehicles) ||
            existStandingLeadingVehicle.holds(globallyTick)) implies
            !inStandStill.holds(globallyTick)
      }
    }

val inVehicleQueue = predicate<TimeStep, Vehicle>("In Vehicle Queue") { _, _ -> true }

val inSlowMovingTraffic = predicate<TimeStep, Vehicle>("In Slow Moving Traffic") { _, _ -> true }

val slightlyHigherSpeed =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Slightly Higher Speed") { _, _ -> true }

val i2DrivingFasterThenLeftTraffic =
    predicate<TimeStep>("I_2 Driving Faster Than Left Traffic") { tick ->
      globally(tick) { globallyTick ->
        globallyTick.nonEgoVehicles.all { otherVehicle ->
          (isOnLeftLaneOf.holds(globallyTick, otherVehicle to globallyTick.ego) and
              isDrivingFaster.holds(globallyTick, otherVehicle to globallyTick.ego)) implies
              ((inVehicleQueue.holds(globallyTick, otherVehicle) or
                  inSlowMovingTraffic.holds(globallyTick, otherVehicle) or
                  inCongestion.holds(
                      tick, otherVehicle to globallyTick.getOtherVehicles(otherVehicle)) or
                  slightlyHigherSpeed.holds(globallyTick, globallyTick.ego to otherVehicle)))
        }
      }
    }
