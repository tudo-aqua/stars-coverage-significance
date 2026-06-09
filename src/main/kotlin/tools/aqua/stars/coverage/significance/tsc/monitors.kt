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

package tools.aqua.stars.coverage.significance.tsc

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import tools.aqua.stars.core.evaluation.Predicate.Companion.predicate
import tools.aqua.stars.core.evaluation.VariablePredicate.Companion.predicate
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.Interval
import tools.aqua.stars.logic.kcmftbl.firstorder.exists
import tools.aqua.stars.logic.kcmftbl.future.globally
import tools.aqua.stars.logic.kcmftbl.past.once
import tools.aqua.stars.logic.kcmftbl.past.previous

/** Infix function for logical implication. */
infix fun Boolean.implies(other: Boolean): Boolean = !this || other

/** General Traffic Rules: G_0 Accidents - Predicate implementation. */
val g0Accidents =
    predicate<TimeStep>("G0 Accidents") { startingTick ->
      globally(startingTick) { tick ->
        tick.nonEgoVehicles.all { otherVehicleGlobally ->
          !collidesWith.holds(tick, tick.ego to otherVehicleGlobally) &&
              !collidesWith.holds(tick, otherVehicleGlobally to tick.ego)
        }
      }
    }

// region G_0 helpers
/** Predicate for checking whether two vehicles collide with each other. */
val collidesWith =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Collides With") { tick, (ego, otherVehicle) ->
      exists(tick.collisionsInTick) { collision ->
        collision.colliderVehicle == ego && collision.victimVehicle == otherVehicle
      }
    }
// endregion

/**
 * A recent cut-in (identified as a cut-in start within the last tCutIn seconds) temporarily relaxes
 * the requirement G_1.
 */
const val TIME_CUT_IN: Long = 3_000L

/** General Traffic Rules: G_1 Safe Distance To Preceding Vehicle - Predicate implementation. */
val g1SafeDistanceToPrecedingVehicle =
    predicate<TimeStep>("G1 Safe Distance To Preceding Vehicle") { startingTick ->
      globally(startingTick) { globallyTick ->
        globallyTick.nonEgoVehicles.all { otherVehicleGlobally ->
          (isOnSameLane.holds(globallyTick, otherVehicleGlobally to globallyTick.ego) &&
              isInFrontOfAbsolute.holds(globallyTick, otherVehicleGlobally to globallyTick.ego) &&
              !once(
                  globallyTick,
                  interval =
                      Interval(
                          TickDifferenceMilliseconds(0L),
                          TickDifferenceMilliseconds(TIME_CUT_IN))) { onceTick ->
                    cutIn.holds(
                        onceTick,
                        onceTick.getVehicleById(otherVehicleGlobally.vehicleId) to onceTick.ego) &&
                        previous(onceTick) { previousTick ->
                          !cutIn.holds(
                              previousTick,
                              previousTick.getVehicleById(otherVehicleGlobally.vehicleId) to
                                  previousTick.ego)
                        }
                  }) implies
              keepSafeDistancePreceding.holds(
                  globallyTick, globallyTick.ego to otherVehicleGlobally)
        }
      }
    }

// region G_1 helpers
/**
 * Predicate for checking, whether there was a cut-in maneuver of another vehicle in front of the
 * ego vehicle.
 */
val cutIn =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Cut In") {
        startingTick,
        (egoStartingTick, otherVehicleStartingTick) ->
      isOnSameLane.holds(startingTick, egoStartingTick to otherVehicleStartingTick) &&
          previous(startingTick) { previousTick ->
            !isOnSameLane.holds(
                previousTick,
                previousTick.ego to
                    previousTick.getVehicleById(otherVehicleStartingTick.vehicleId)) &&
                isBesidesOf.holds(
                    previousTick,
                    previousTick.ego to
                        previousTick.getVehicleById(otherVehicleStartingTick.vehicleId))
          } &&
          isInFrontOfAbsolute.holds(startingTick, otherVehicleStartingTick to egoStartingTick)
    }

/** Reaction time t_d used in the paper’s dsafe formula. */
const val SAFE_DISTANCE_REACTION_TIME_SECONDS: Float = 0.3f

/**
 * Helper: safe-distance condition based on the paper’s dsafe: dsafe = v_ego * t_d +
 * v_ego^2/(2*a_ego) - v_prec^2/(2*a_prec).
 *
 * We use emergencyDecelMetersPerSecondSquared as |a_min| (positive magnitude). Clamp dsafe at 0 to
 * avoid negative requirements when the preceding car is much faster.
 */
val keepSafeDistancePreceding =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Has Safe Distance To Preceding") {
        _,
        (ego, preceding) ->
      val gap = preceding.backBumperPositionOnLaneMeters - ego.frontBumperPositionOnLaneMeters

      val aEgo = ego.emergencyDecelMetersPerSecondSquared
      val aPrec = preceding.emergencyDecelMetersPerSecondSquared

      val vEgo = max(0.0f, ego.speedMetersPerSecond)
      val vPrec = max(0.0f, preceding.speedMetersPerSecond)

      val dSafe =
          vPrec.pow(2) / (-2.0f * abs(aPrec)) - vEgo.pow(2) / (-2.0f * abs(aEgo)) +
              (vEgo * SAFE_DISTANCE_REACTION_TIME_SECONDS)

      gap > dSafe
    }

// endregion

/** General Traffic Rules: G_2 Abrupt Braking - Predicate implementation. */
val g2EmergencyBraking =
    predicate<TimeStep>("G2 Emergency Braking") { startingTick ->
      globally(startingTick) { globallyTick ->
        !isBrakingEmergently.holds(globallyTick, globallyTick.ego)
      }
    }

// region G_2 helpers

/** Emergency braking parameter: -7.0 m/s^2. */
const val EMERGENCY_BRAKING_THRESHOLD_MPS2: Float = -7.0f

/** Helper: ego brakes emergently. (More negative acceleration than the abrupt threshold.) */
val isBrakingEmergently =
    predicate<TimeStep, Vehicle>("Is Braking Emergently") { _, vehicle ->
      vehicle.accelerationMetersPerSecondSquared < EMERGENCY_BRAKING_THRESHOLD_MPS2
    }

// endregion
/** General Traffic Rules: G_3 Maximum Speed Limit - Predicate implementation. */
val g3MaximumSpeedLimit =
    predicate<TimeStep>("G3 Maximum Speed Limit") { startingTick ->
      globally(startingTick) { globallyTick ->
        keepLaneSpeedLimit.holds(globallyTick) && keepFovSpeedLimit.holds(globallyTick)
      }
    }

// region G_3 helpers
/** Predicate for keeping the speed limit of the current lane. */
val keepLaneSpeedLimit =
    predicate<TimeStep>("Keep Lane Speed Limit") { startingTick ->
      startingTick.ego.speedMetersPerSecond <=
          startingTick.ego.currentLane.speedLimitMetersPerSecond
    }

/** Predicate for keeping the speed limit within the field of view (FOV) distance. */
val keepFovSpeedLimit =
    predicate<TimeStep>("Keep FOV Speed Limit") { startingTick ->
      startingTick.ego.speedMetersPerSecond <=
          39.812 // Solved for a=-9m/s^2, td=0.3s, s_fov=FIELD_OF_VIEW_DISTANCE_METERS
    }
// endregion
/**
 * Parameter for minimum acceleration to consider the ego vehicle as preserving the traffic flow,
 * even if there is a slow leading vehicle.
 */
const val TRAFFIC_FLOW_PRESERVATION_MIN_ACCELERATION = 1.0f

/** General Traffic Rules: G_4 Traffic Flow - Predicate implementation. */
val g4TrafficFlow =
    predicate<TimeStep>("G4 Traffic Flow") { startingTick ->
      globally(startingTick) { globallyTick ->
        !slowLeadingVehicle.holds(globallyTick) implies
            (globallyTick.ego.accelerationMetersPerSecondSquared >
                TRAFFIC_FLOW_PRESERVATION_MIN_ACCELERATION || preservesFlow.holds(globallyTick))
      }
    }

// region G_4 helpers

/** Paper parameter Δv_fl (Table II): 15.0 m/s. */
const val TRAFFIC_FLOW_DELTA_V_FL_MPS: Float = 15.0f

/** Predicate for checking whether there is a slow leading vehicle in front of the ego vehicle. */
val slowLeadingVehicle =
    predicate<TimeStep>("Slow Leading Vehicle") { startingTick ->
      val egoStartingTick = startingTick.ego

      startingTick.nonEgoVehicles.any { nonEgoVehicleStartingTick ->
        isOnSameLane.holds(startingTick, nonEgoVehicleStartingTick to egoStartingTick) &&
            isInFrontOfAbsolute.holds(startingTick, nonEgoVehicleStartingTick to egoStartingTick) &&
            // v_max2(nonEgoVehicleStartingTick) - v(nonEgoVehicleStartingTick) >= Δv_fl
            ((nonEgoVehicleStartingTick.currentLane.speedLimitMetersPerSecond -
                nonEgoVehicleStartingTick.speedMetersPerSecond) >= TRAFFIC_FLOW_DELTA_V_FL_MPS)
      }
    }

/** The maximum speed limit: 130km/h in m/s. */
const val HIGHWAY_MIN_SPEED: Float = 60.0f / 3.6f

/** Predicate for checking whether the ego vehicle preserves the traffic flow. */
val preservesFlow =
    predicate<TimeStep>("Preserves Flow") { startingTick ->
      startingTick.ego.speedMetersPerSecond >= HIGHWAY_MIN_SPEED // 60 km/h in m/s
    }
// endregion

/** General Traffic Rules: I_1 Stopping - Predicate implementation. */
val i1Stopping =
    predicate<TimeStep>("I1 Too Slow Driving") { startingTick ->
      globally(startingTick) { globallyTick ->
        !existsSlowLeadingVehicle.holds(globallyTick) implies !egoTooSLow.holds(globallyTick)
      }
    }

// region I_1 helpers
/**
 * Parameter for maximum speed of a leading vehicle to be considered slow and thus justifying the
 * ego vehicle driving very slowly.
 */
const val SLOW_LEADING_VEHICLE_SPEED_KMH: Float = 30.0f
/** Parameter for minimum speed of the ego vehicle to not be considered too slow. */
const val MIN_SPEED_EGO_KMH: Float = 10.0f

/**
 * Predicate for checking whether the ego vehicle is driving too slowly (below a minimum speed),
 * which would be unjustified if there is no slow leading vehicle in front of it.
 */
val egoTooSLow =
    predicate<TimeStep>("In Stand Still") { startingTick ->
      startingTick.ego.speedKmPerHour <= MIN_SPEED_EGO_KMH
    }

/**
 * Predicate for checking whether there is a slow leading vehicle in front of the ego vehicle, which
 * would justify the ego vehicle driving very slowly.
 */
val existsSlowLeadingVehicle =
    predicate<TimeStep>("Exists Slow Leading Vehicle") { startingTick ->
      exists(startingTick.nonEgoVehicles) { nonEgoVehicleStartingTick ->
        isOnSameLane.holds(startingTick, nonEgoVehicleStartingTick to startingTick.ego) &&
            isInFrontOfAbsolute.holds(
                startingTick, nonEgoVehicleStartingTick to startingTick.ego) &&
            nonEgoVehicleStartingTick.speedKmPerHour <= SLOW_LEADING_VEHICLE_SPEED_KMH
      }
    }
// endregion

/** General Traffic Rules: I_2 Driving Faster Than Left Traffic - Predicate implementation. */
val i2DrivingFasterThenLeftTraffic =
    predicate<TimeStep>("I2 Driving Faster Than Left Traffic") { startingTick ->
      globally(startingTick) { globallyTick ->
        !exists(globallyTick.nonEgoVehicles) { nonEgoVehicleGloballyTick ->
          rightOvertaking.holds(globallyTick, globallyTick.ego to nonEgoVehicleGloballyTick) &&
              (nonEgoVehicleGloballyTick.speedKmPerHour >= 60.0 ||
                  (globallyTick.ego.speedKmPerHour - nonEgoVehicleGloballyTick.speedKmPerHour) >=
                      10.0)
        }
      }
    }

// region I2 Helper
/**
 * Helper: right overtaking: ego is on the left lane of another vehicle, and overtakes it from the
 * right side (i.e., was behind it in the previous tick, and is now alongside or in front of it).
 */
val rightOvertaking =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Right Overtaking") {
        startingTick,
        (egoStartingTick, otherVehicleStartingTick) ->
      previous(startingTick) { previousTick ->
        isOnLeftLaneOf.holds(
            previousTick,
            previousTick.getVehicleById(otherVehicleStartingTick.vehicleId) to previousTick.ego) &&
            previousTick.ego.frontBumperPositionOnLaneMeters <
                previousTick
                    .getVehicleById(otherVehicleStartingTick.vehicleId)
                    .backBumperPositionOnLaneMeters
      } &&
          isOnLeftLaneOf.holds(
              startingTick,
              startingTick.getVehicleById(otherVehicleStartingTick.vehicleId) to
                  startingTick.ego) &&
          startingTick.ego.frontBumperPositionOnLaneMeters >=
              startingTick
                  .getVehicleById(otherVehicleStartingTick.vehicleId)
                  .backBumperPositionOnLaneMeters
    }
// endregion
