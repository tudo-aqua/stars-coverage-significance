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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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

/** General Traffic Rules: G_0 Accidents - Predicate implementation. */
val g0Accidents =
    predicate<TimeStep>("G_0 Accidents") { startingTick ->
      globally(startingTick) { tick ->
        tick.nonEgoVehicles.all { otherVehicle ->
          !collidesWith.holds(tick, tick.ego to otherVehicle)
        }
      }
    }

// region G_0 helpers
/** Predicate for checking whether two vehicles collide with each other. */
val collidesWith =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Collides With") { tick, (ego, otherVehicle) ->
      tick.collisionsInTick.any { collision ->
        (collision.colliderVehicle == ego && collision.victimVehicle == otherVehicle) ||
            (collision.colliderVehicle == otherVehicle && collision.victimVehicle == ego)
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
    predicate<TimeStep>("G_1 Safe Distance To Preceding Vehicle") { startingTick ->
      globally(startingTick) { tick ->
        tick.nonEgoVehicles.all { otherVehicle ->
          (isInFrontOnSameLane.holds(tick, otherVehicle to tick.ego) &&
              !once(
                  tick,
                  interval =
                      Interval(
                          TickDifferenceMilliseconds(0L),
                          TickDifferenceMilliseconds(TIME_CUT_IN))) { onceTick ->
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

// region G_1 helpers
/**
 * Predicate for checking, whether there was a cut-in maneuver of another vehicle in front of the
 * ego vehicle.
 */
val cutIn =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Cut In") { startingTick, (ego, otherVehicle) ->
      isOnSameLane.holds(startingTick, ego to otherVehicle) &&
          previous(startingTick) { previousTick ->
            !isOnSameLane.holds(
                previousTick,
                previousTick.ego to previousTick.getVehicleById(otherVehicle.vehicleId)) &&
                isBesidesOf.holds(
                    previousTick,
                    previousTick.ego to previousTick.getVehicleById(otherVehicle.vehicleId))
          } &&
          isInFrontOfAbsolute.holds(startingTick, otherVehicle to ego)
    }

/** Reaction time t_d used in the paper’s dsafe formula. */
const val SAFE_DISTANCE_REACTION_TIME_SECONDS: Float = 0.3f

/**
 * Helper: bumper-to-bumper gap to a preceding vehicle on the same lane: gap = rear(preceding) -
 * front(ego).
 */
val hasPositiveBumperGapToPreceding =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Has Positive Gap To Preceding") {
        _,
        (ego, preceding) ->
      (preceding.backBumperPositionOnLaneMeters - ego.frontBumperPositionOnLaneMeters) > 0.0f
    }

/**
 * Helper: safe-distance condition based on the paper’s dsafe: dsafe = v_ego * t_d +
 * v_ego^2/(2*a_ego) - v_prec^2/(2*a_prec).
 *
 * We use emergencyDecelMetersPerSecondSquared as |a_min| (positive magnitude). Clamp dsafe at 0 to
 * avoid negative requirements when the preceding car is much faster.
 */
val hasSafeDistanceToPreceding =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Has Safe Distance To Preceding") {
        _,
        (ego, preceding) ->
      val gap = preceding.backBumperPositionOnLaneMeters - ego.frontBumperPositionOnLaneMeters

      val aEgo = max(1e-3f, ego.emergencyDecelMetersPerSecondSquared)
      val aPrec = max(1e-3f, preceding.emergencyDecelMetersPerSecondSquared)

      val vEgo = max(0.0f, ego.speedMetersPerSecond)
      val vPrec = max(0.0f, preceding.speedMetersPerSecond)

      val dSafe =
          max(
              0.0f,
              vEgo * SAFE_DISTANCE_REACTION_TIME_SECONDS + (vEgo * vEgo) / (2.0f * aEgo) -
                  (vPrec * vPrec) / (2.0f * aPrec))

      gap > dSafe
    }

/**
 * Predicate for keeping a safe distance to the preceding vehicle.
 *
 * True iff:
 * - preceding is in front of ego on the same lane, and
 * - the bumper-to-bumper gap is positive, and
 * - the gap is larger than the paper-based safe distance dsafe.
 */
val keepSafeDistancePreceding =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Keep Safe Distance To Preceding") {
        tick,
        (ego, preceding) ->
      isInFrontOnSameLane.holds(tick, preceding to ego) &&
          hasPositiveBumperGapToPreceding.holds(tick, ego to preceding) &&
          hasSafeDistanceToPreceding.holds(tick, ego to preceding)
    }

// endregion

/** General Traffic Rules: G_2 Unnecessary Braking - Predicate implementation. */
val g2UnnecessaryBraking =
    predicate<TimeStep>("G_2 Unnecessary Braking") { tick ->
      globally(tick) { globallyTick -> !unnecessaryBraking.holds(globallyTick, globallyTick.ego) }
    }

// region G_2 helpers
/** Paper parameter a_abrupt (Table II): -2.0 m/s^2. */
const val ABRUPT_BRAKING_THRESHOLD_MPS2: Float = -2.0f

/** Helper: ego brakes abruptly. (More negative acceleration than the abrupt threshold.) */
val isBrakingAbruptly =
    predicate<TimeStep, Vehicle>("Is Braking Abruptly") { tick, vehicle ->
      vehicle.accelerationMetersPerSecondSquared < ABRUPT_BRAKING_THRESHOLD_MPS2
    }

/**
 * Paper-level predicate: unnecessary_braking(x_k, X_not_k)
 *
 * Ego-first convention: unnecessaryBraking(tick) evaluates ego vs all non-ego vehicles in tick.
 *
 * Case A:
 * - ego brakes abruptly
 * - no leading vehicle exists on same lane
 *
 * Case B:
 * - ego brakes abruptly
 * - there is a closest leading vehicle on same lane (leader)
 * - ego still keeps safe distance to leader
 * - (a_ego - a_leader) < a_abrupt (paper’s “relative abruptness” check)
 */
val unnecessaryBraking =
    predicate<TimeStep, Vehicle>("Unnecessary Braking") { tick, vehicleK ->
      (vehicleK.accelerationMetersPerSecondSquared < 0.0f) implies
          ((tick.getOtherVehicles(vehicleK).none { vehicleP ->
            isInFrontOnSameLane.holds(tick, vehicleP to vehicleK) &&
                isBrakingAbruptly.holds(tick, vehicleK)
          }) ||
              (tick.getOtherVehicles(vehicleK).any { vehicleP ->
                keepSafeDistancePreceding.holds(tick, vehicleK to vehicleP) &&
                    isInFrontOnSameLane.holds(tick, vehicleP to vehicleK) &&
                    (vehicleK.accelerationMetersPerSecondSquared -
                        vehicleP.accelerationMetersPerSecondSquared < ABRUPT_BRAKING_THRESHOLD_MPS2)
              }))
    }
// endregion

/** General Traffic Rules: G_3 Maximum Speed Limit - Predicate implementation. */
val g3MaximumSpeedLimit =
    predicate<TimeStep>("G_3 Maximum Speed Limit") { tick ->
      globally(tick) { globallyTick ->
        keepLaneSpeedLimit.holds(globallyTick) && keepFovSpeedLimit.holds(globallyTick)
      }
    }

// region G_3 helpers
/** Maximum distance to a vehicle in front to consider for FOV speed limit checking. */
const val FIELD_OF_VIEW_DISTANCE_METERS: Float = VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO

/** Predicate for keeping the speed limit of the current lane. */
val keepLaneSpeedLimit =
    predicate<TimeStep>("Keep Lane Speed Limit") { tick ->
      tick.ego.speedMetersPerSecond <= tick.ego.currentLane.speedLimitMetersPerSecond
    }

/** Predicate for keeping the speed limit within the field of view (FOV) distance. */
val keepFovSpeedLimit =
    predicate<TimeStep>("Keep FOV Speed Limit") { tick ->
      val v = max(0.0f, tick.ego.speedMetersPerSecond)
      val a = max(1e-3f, tick.ego.emergencyDecelMetersPerSecondSquared) // |a_min|
      val td = SAFE_DISTANCE_REACTION_TIME_SECONDS

      val requiredStoppingDistance = v * td + (v * v) / (2.0f * a)
      requiredStoppingDistance <= FIELD_OF_VIEW_DISTANCE_METERS
    }
// endregion

/** General Traffic Rules: G_4 Traffic Flow - Predicate implementation. */
val g4TrafficFlow =
    predicate<TimeStep>("G_4 Traffic Flow") { tick ->
      globally(tick) { globallyTick ->
        !slowLeadingVehicle.holds(globallyTick) implies preservesFlow.holds(globallyTick)
      }
    }

// region G_4 helpers

/** Paper parameter Δv_fl (Table II): 15.0 m/s. */
const val TRAFFIC_FLOW_DELTA_V_FL_MPS: Float = 15.0f

/** Predicate for checking whether there is a slow leading vehicle in front of the ego vehicle. */
val slowLeadingVehicle =
    predicate<TimeStep>("Slow Leading Vehicle") { tick ->
      val ego = tick.ego

      tick.nonEgoVehicles.any { other ->
        isInFrontOnSameLane.holds(tick, other to ego) &&
            // v_max2(other) - v(other) >= Δv_fl
            ((other.currentLane.speedLimitMetersPerSecond - other.speedMetersPerSecond) >=
                TRAFFIC_FLOW_DELTA_V_FL_MPS)
      }
    }

/** Predicate for checking whether the ego vehicle preserves the traffic flow. */
val preservesFlow =
    predicate<TimeStep>("Preserves Flow") { tick ->
      val ego = tick.ego

      // v_max_sl(ego)
      val vLaneMax = ego.currentLane.speedLimitMetersPerSecond

      // v_fov_max(ego): solve v*td + v^2/(2a) <= s_fov for v (positive root)
      val v = max(0.0f, ego.speedMetersPerSecond)
      val a = max(1e-3f, ego.emergencyDecelMetersPerSecondSquared) // magnitude |a_min|
      val td = SAFE_DISTANCE_REACTION_TIME_SECONDS
      val sFov = VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO

      val vFovMax = -a * td + sqrt(max(0.0f, (a * td) * (a * td) + 2.0f * a * sFov))

      // v_max1(ego) = min(v_br, v_fov, v*_sl, v_type) -> here: min(vFovMax, vLaneMax)
      val vMax1 = min(vFovMax, vLaneMax)

      // preserves_flow: v_max1 - v < Δv_fl
      (vMax1 - v) < TRAFFIC_FLOW_DELTA_V_FL_MPS
    }

// endregion

/** General Traffic Rules: I_1 Stopping - Predicate implementation. */
val i1Stopping =
    predicate<TimeStep>("I_1 Stopping") { tick ->
      globally(tick) { globallyTick ->
        !(inCongestion.holds(globallyTick, globallyTick.ego to globallyTick.nonEgoVehicles) ||
            existStandingLeadingVehicle.holds(globallyTick)) implies
            !inStandStill.holds(globallyTick)
      }
    }

// region I_1 helpers
/** Paper parameter verr (Table II): 0.01 m/s. */
const val SPEED_MEASUREMENT_ERROR_VERR_MPS: Float = 0.01f

/** Paper parameter vcon (Table II): 2.78 m/s. */
const val CONGESTION_MAX_SPEED_VCON_MPS: Float = 2.78f

/** Paper parameter ncon (Table II): 3. */
const val CONGESTION_MIN_VEHICLES_NCON: Int = 3

/** inStandstill(xego): -verr <= v_ego <= verr. */
val inStandStill =
    predicate<TimeStep>("In Stand Still") { tick ->
      abs(tick.ego.speedMetersPerSecond) <= SPEED_MEASUREMENT_ERROR_VERR_MPS
    }

/**
 * existStandingLeadingVehicle(xego, X¬ego): ∃xp: same lane AND in front (absolute) AND xp is in
 * standstill.
 */
val existStandingLeadingVehicle =
    predicate<TimeStep>("Exist Standing Leading Vehicle") { tick ->
      val ego = tick.ego
      tick.nonEgoVehicles.any { other ->
        isOnSameLane.holds(tick, other to ego) &&
            isInFrontOfAbsolute.holds(tick, other to ego) &&
            abs(other.speedMetersPerSecond) <= SPEED_MEASUREMENT_ERROR_VERR_MPS
      }
    }

/**
 * inCongestion(xego, X¬ego): count of vehicles in front on same lane with v <= vcon is at least
 * ncon.
 *
 * Note: signature keeps your (ego, list) style; the predicate uses the passed list.
 */
val inCongestion =
    predicate<TimeStep, Pair<Vehicle, List<Vehicle>>>("In Congestion") { tick, (ego, others) ->
      val slowInFrontCount =
          others.count { other ->
            isOnSameLane.holds(tick, other to ego) &&
                isInFrontOfAbsolute.holds(tick, other to ego) &&
                other.speedMetersPerSecond <= CONGESTION_MAX_SPEED_VCON_MPS
          }

      slowInFrontCount >= CONGESTION_MIN_VEHICLES_NCON
    }
// endregion

/** General Traffic Rules: I_2 Driving Faster Than Left Traffic - Predicate implementation. */
val i2DrivingFasterThenLeftTraffic =
    predicate<TimeStep>("I_2 Driving Faster Than Left Traffic") { tick ->
      globally(tick) { globallyTick ->
        globallyTick.nonEgoVehicles.all { otherVehicle ->
          (isOnLeftLaneOf.holds(globallyTick, otherVehicle to globallyTick.ego) &&
              drivesFasterAbsolute.holds(globallyTick, otherVehicle to globallyTick.ego)) implies
              (inVehicleQueue.holds(globallyTick, otherVehicle) ||
                  inSlowMovingTraffic.holds(globallyTick, otherVehicle) ||
                  inCongestion.holds(
                      globallyTick, otherVehicle to globallyTick.getOtherVehicles(otherVehicle)) ||
                  slightlyHigherSpeed.holds(globallyTick, globallyTick.ego to otherVehicle))
        }
      }
    }

// region I_2 helpers
/** Paper parameter vso (Table II): 5.55 m/s. */
const val SLIGHTLY_HIGHER_SPEED_VSO_MPS: Float = 5.55f

/** Paper parameter vsmt (Table II): 8.33 m/s. */
const val SLOW_MOVING_TRAFFIC_VSMT_MPS: Float = 8.33f

/** Paper parameter vqv (Table II): 16.67 m/s. */
const val VEHICLE_QUEUE_VQV_MPS: Float = 16.67f

/** Paper parameters nsmt/nqv (Table II): 3. */
const val SLOW_MOVING_TRAFFIC_MIN_COUNT_NSMT: Int = 3

/** Paper parameters nsmt/nqv (Table II): 3. */
const val VEHICLE_QUEUE_MIN_COUNT_NQV: Int = 3

/** slightlyHigherSpeed(xego, xleft): 0 < v(ego) - v(left) < vso. */
val slightlyHigherSpeed =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Slightly Higher Speed") { _, (ego, other) ->
      val dv = ego.speedMetersPerSecond - other.speedMetersPerSecond
      dv > 0.0f && dv < SLIGHTLY_HIGHER_SPEED_VSO_MPS
    }

/**
 * inVehicleQueue(xo, X¬o): analogous to in_congestion Count vehicles in front of xo on the same
 * lane with v <= vqv, require count >= nqv.
 *
 * Signature is Vehicle-only in your code, so we use tick.getOtherVehicles(vehicle).
 */
val inVehicleQueue =
    predicate<TimeStep, Vehicle>("In Vehicle Queue") { tick, vehicle ->
      val others = tick.getOtherVehicles(vehicle)

      val countSlowInFront =
          others.count { other ->
            isOnSameLane.holds(tick, other to vehicle) &&
                isInFrontOfAbsolute.holds(tick, other to vehicle) &&
                other.speedMetersPerSecond <= VEHICLE_QUEUE_VQV_MPS
          }

      countSlowInFront >= VEHICLE_QUEUE_MIN_COUNT_NQV
    }

/**
 * inSlowMovingTraffic(xo, X¬o): analogous to in_congestion Count vehicles in front of xo on the
 * same lane with v <= vsmt, require count >= nsmt.
 *
 * Signature is Vehicle-only in your code, so we use tick.getOtherVehicles(vehicle).
 */
val inSlowMovingTraffic =
    predicate<TimeStep, Vehicle>("In Slow Moving Traffic") { tick, vehicle ->
      val others = tick.getOtherVehicles(vehicle)

      val countSlowInFront =
          others.count { other ->
            isOnSameLane.holds(tick, other to vehicle) &&
                isInFrontOfAbsolute.holds(tick, other to vehicle) &&
                other.speedMetersPerSecond <= SLOW_MOVING_TRAFFIC_VSMT_MPS
          }

      countSlowInFront >= SLOW_MOVING_TRAFFIC_MIN_COUNT_NSMT
    }

/**
 * Paper-faithful: drives_faster(xk, xp) ⇔ v(xk) > v(xp)
 *
 * Convention: first vehicle is compared against the second.
 */
val drivesFasterAbsolute =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Drives Faster (Absolute)") { _, (first, second) ->
      first.speedMetersPerSecond > second.speedMetersPerSecond
    }
// endregion
