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

import kotlin.math.max
import tools.aqua.stars.core.evaluation.Predicate.Companion.predicate
import tools.aqua.stars.core.evaluation.VariablePredicate.Companion.predicate
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.firstorder.exists

/** TTC in seconds below which a front approach is classified as critical. */
const val TTC_CRITICAL_SECONDS: Double = 6.0

/** Time gap in seconds in which a vehicle is considered critical for the ego vehicle. */
const val TG_LATERAL_CRITICAL_SECONDS: Double = 1.0

/** Time gap in seconds in which a vehicle is considered relevant for the ego vehicle. */
const val TG_LATERAL_RELEVANT_SECONDS: Double = 6.0

/** Time gap in seconds in which a vehicle is considered relevant for the ego vehicle. */
const val TG_LONGITUDINAL_CRITICAL_SECONDS: Double = 1.0

/** Time gap in seconds in which a vehicle is considered relevant for the ego vehicle. */
const val TG_LONGITUDINAL_RELEVANT_SECONDS: Double = 10.0

/** TTC in seconds below which a vehicle is considered critical for the ego vehicle. */
const val TTC_LONGITUDINAL_CRITICAL_SECONDS: Double = 2.0

/** Epsilon for considering a vehicle as being "besides" the ego vehicle. */
private const val LONGITUDINAL_BUMPER_EPSILON_METERS: Float = 0.5f

/** Minimum speed in meters per second for which the speed normalization is used. */
private const val MIN_SPEED_NORMALIZATION_MPS: Float = 0.1f

/** Lane index modifier for the left lane. */
const val LEFT_LANE_INDEX_MODIFIER = +1

/** Lane index modifier for the right lane. */
const val RIGHT_LANE_INDEX_MODIFIER = -1

/**
 * Computes the time-to-collision from [ego] to vehicle [other] ahead of ego.
 *
 * Returns [Double.POSITIVE_INFINITY] when [other] is not in front of [ego] or when ego is not
 * closing on [other].
 */
private fun ttcFront(ego: Vehicle, other: Vehicle): Double {
  val gap = other.backBumperPositionOnLaneMeters - ego.frontBumperPositionOnLaneMeters
  val closingSpeed = ego.speedMetersPerSecond - other.speedMetersPerSecond
  return if (gap > 0.0 && closingSpeed > 0.0) gap.toDouble() / closingSpeed
  else Double.POSITIVE_INFINITY
}

/**
 * Computes the time-to-collision from vehicle [other] behind ego to [ego].
 *
 * Returns [Double.POSITIVE_INFINITY] when [other] is not behind [ego] or when [other] is not
 * closing on [ego].
 */
private fun ttcRear(ego: Vehicle, other: Vehicle): Double {
  val gap = ego.backBumperPositionOnLaneMeters - other.frontBumperPositionOnLaneMeters
  val closingSpeed = other.speedMetersPerSecond - ego.speedMetersPerSecond
  return if (gap > 0.0 && closingSpeed > 0.0) gap.toDouble() / closingSpeed
  else Double.POSITIVE_INFINITY
}

/**
 * Computes the time gap from [vehicleBehind] to [vehicleFront].
 *
 * Returns [Double.POSITIVE_INFINITY] when [vehicleFront] is not ahead of [vehicleBehind] or when
 * [vehicleBehind] has non-positive speed.
 */
fun timeGap(vehicleBehind: Vehicle, vehicleFront: Vehicle): Double {
  val distanceBetween =
      vehicleFront.backBumperPositionOnLaneMeters - vehicleBehind.frontBumperPositionOnLaneMeters
  val speed = vehicleBehind.speedMetersPerSecond
  return if (distanceBetween > 0.0 && speed > 0.0) distanceBetween.toDouble() / speed
  else Double.POSITIVE_INFINITY
}

/**
 * Computes a signed longitudinal time gap from [ego] to [other].
 *
 * Positive values mean [other] is ahead, negative values mean [other] is behind, and values near
 * zero include overlapping or directly beside configurations.
 */
private fun signedTimeGapSeconds(ego: Vehicle, other: Vehicle): Double {
  val frontClearance = other.backBumperPositionOnLaneMeters - ego.frontBumperPositionOnLaneMeters
  val rearClearance = ego.backBumperPositionOnLaneMeters - other.frontBumperPositionOnLaneMeters

  val signedLongitudinalClearance =
      when {
        frontClearance > LONGITUDINAL_BUMPER_EPSILON_METERS -> frontClearance
        rearClearance > LONGITUDINAL_BUMPER_EPSILON_METERS -> -rearClearance
        else -> 0.0
      }

  val speedNormalization =
      max(max(ego.speedMetersPerSecond, other.speedMetersPerSecond), MIN_SPEED_NORMALIZATION_MPS)
  return signedLongitudinalClearance.toDouble() / speedNormalization.toDouble()
}

private fun isInsideSignedTimeGapWindow(
    ego: Vehicle,
    other: Vehicle,
    minSeconds: Double,
    maxSeconds: Double,
): Boolean = signedTimeGapSeconds(ego, other) in minSeconds..maxSeconds

/**
 * Predicate that checks if the vehicle in front of the ego vehicle is moving at about the same
 * speed as the ego vehicle.
 */
val isInside6SecondsTimeGapFrontOrBack =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is inside 6 seconds time gap front or back") {
        _,
        vehiclePair ->
      val ego = vehiclePair.first
      val other = vehiclePair.second
      isInsideSignedTimeGapWindow(
          ego, other, -TG_LATERAL_RELEVANT_SECONDS, TG_LATERAL_RELEVANT_SECONDS)
    }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val otherVehicleIsCriticalForEgo =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Other Vehicle is Critical for Ego") {
        _,
        vehiclePair ->
      val ego = vehiclePair.first
      val otherVehicle = vehiclePair.second
      isInsideSignedTimeGapWindow(
          ego,
          otherVehicle,
          -TG_LATERAL_CRITICAL_SECONDS,
          TG_LATERAL_CRITICAL_SECONDS,
      ) ||
          ttcFront(ego, otherVehicle) < TTC_CRITICAL_SECONDS ||
          ttcRear(ego, otherVehicle) < TTC_CRITICAL_SECONDS
    }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val hasRelevantVehicleOnLeftLane =
    predicate<TimeStep>("Has Relevant Vehicle on Left Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + LEFT_LANE_INDEX_MODIFIER &&
            isInside6SecondsTimeGapFrontOrBack.holds(tick, tick.ego to otherVehicle)
      }
    }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val hasRelevantVehicleOnRightLane =
    predicate<TimeStep>("Has Relevant Vehicle on Right Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + RIGHT_LANE_INDEX_MODIFIER &&
            isInside6SecondsTimeGapFrontOrBack.holds(tick, tick.ego to otherVehicle)
      }
    }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val canMoveLeft = predicate<TimeStep>("Can Move Left") { tick -> !canNotMoveLeft.holds(tick) }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val canMoveRight = predicate<TimeStep>("Can Move Right") { tick -> !canNotMoveRight.holds(tick) }

/** Predicate that checks if the vehicle is critical for the ego vehicle. */
val canNotMoveLeft =
    predicate<TimeStep>("Cannot Move Left") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + LEFT_LANE_INDEX_MODIFIER &&
            otherVehicleIsCriticalForEgo.holds(tick, tick.ego to otherVehicle)
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * left lane.
 */
val canNotMoveRight =
    predicate<TimeStep>("Cannot Move Right") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + RIGHT_LANE_INDEX_MODIFIER &&
            otherVehicleIsCriticalForEgo.holds(tick, tick.ego to otherVehicle)
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasRelevantVehicleOnLeftLaneOfLeftLane =
    predicate<TimeStep>("Has Relevant Vehicle on Left Lane of Left Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * LEFT_LANE_INDEX_MODIFIER) &&
            isInside6SecondsTimeGapFrontOrBack.holds(tick, tick.ego to otherVehicle)
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasRelevantVehicleOnRightLaneOfRightLane =
    predicate<TimeStep>("Has Relevant Vehicle on Right Lane of Right Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * RIGHT_LANE_INDEX_MODIFIER) &&
            isInside6SecondsTimeGapFrontOrBack.holds(tick, tick.ego to otherVehicle)
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasRelevantVehicleInFront =
    predicate<TimeStep>("Has Relevant Vehicle in Front") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        tick.ego.currentLane.laneIndex == otherVehicle.currentLane.laneIndex &&
            timeGap(tick.ego, otherVehicle) <= TG_LONGITUDINAL_RELEVANT_SECONDS
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasRelevantVehicleInBehind =
    predicate<TimeStep>("Has Relevant Vehicle in Behind") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        tick.ego.currentLane.laneIndex == otherVehicle.currentLane.laneIndex &&
            timeGap(otherVehicle, tick.ego) <= TG_LONGITUDINAL_RELEVANT_SECONDS
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasCriticalVehicleInFront =
    predicate<TimeStep>("Has Critical Vehicle in Front") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        tick.ego.currentLane.laneIndex == otherVehicle.currentLane.laneIndex &&
            timeGap(tick.ego, otherVehicle) < TG_LONGITUDINAL_CRITICAL_SECONDS ||
            ttcFront(tick.ego, otherVehicle) < TTC_LONGITUDINAL_CRITICAL_SECONDS
      }
    }

/**
 * Predicate that checks if the vehicle is critical for the ego vehicle and is not moving to the
 * right lane.
 */
val hasCriticalVehicleInBehind =
    predicate<TimeStep>("Has Critical Vehicle in Behind") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        tick.ego.currentLane.laneIndex == otherVehicle.currentLane.laneIndex &&
            timeGap(otherVehicle, tick.ego) < TG_LONGITUDINAL_CRITICAL_SECONDS ||
            ttcRear(tick.ego, otherVehicle) < TTC_LONGITUDINAL_CRITICAL_SECONDS
      }
    }
