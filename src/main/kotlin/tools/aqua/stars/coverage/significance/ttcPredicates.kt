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
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.firstorder.exists

// ── TTC thresholds ────────────────────────────────────────────────────────────

/** TTC in seconds below which a front approach is classified as critical. */
const val TTC_CRITICAL_SECONDS: Double = 3.0

/**
 * TTC in seconds below which a front approach triggers a warning. Must be greater than
 * [TTC_CRITICAL_SECONDS].
 */
const val TTC_WARNING_SECONDS: Double = 6.0

// ── Corridor bounds ───────────────────────────────────────────────────────────

/** Rear extent of the lateral conflict corridor relative to the ego position (m). */
const val CORRIDOR_REAR_METERS: Float = 20.0f

/** Forward extent of the lateral conflict corridor relative to the ego position (m). */
const val CORRIDOR_FRONT_METERS: Float = 40.0f

// ── TTC helper functions ──────────────────────────────────────────────────────

/**
 * Computes the time-to-collision from [ego] to vehicle [other] ahead of ego.
 *
 * Returns [Double.POSITIVE_INFINITY] when [other] is not in front of [ego] or when ego is not
 * closing on [other].
 */
private fun ttcFront(ego: Vehicle, other: Vehicle): Double {
  val gap = other.positionOnLaneMeters - ego.positionOnLaneMeters
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
  val gap = ego.positionOnLaneMeters - other.positionOnLaneMeters
  val closingSpeed = other.speedMetersPerSecond - ego.speedMetersPerSecond
  return if (gap > 0.0 && closingSpeed > 0.0) gap.toDouble() / closingSpeed
  else Double.POSITIVE_INFINITY
}

// ── Same-lane front TTC predicates ───────────────────────────────────────────

/**
 * Predicate that checks if there is a vehicle ahead on the ego's lane for which the TTC is below
 * [TTC_CRITICAL_SECONDS]. The candidate vehicle must satisfy [isInFrontOnSameLane], which enforces
 * the [[VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM], [VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO]]
 * sensor range.
 */
val hasCriticalTTCFrontSameLane =
    predicate<TimeStep>("hasCriticalTTCFrontSameLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isInFrontOnSameLane.holds(tick, other to tick.ego) &&
            ttcFront(tick.ego, other) < TTC_CRITICAL_SECONDS
      }
    }

/**
 * Predicate that checks if there is a vehicle ahead on the ego's lane for which the TTC is in the
 * warning range [[TTC_CRITICAL_SECONDS], [TTC_WARNING_SECONDS]). A vehicle that already triggers
 * [hasCriticalTTCFrontSameLane] does not also trigger this predicate.
 */
val hasWarningTTCFrontSameLane =
    predicate<TimeStep>("hasWarningTTCFrontSameLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isInFrontOnSameLane.holds(tick, other to tick.ego) &&
            ttcFront(tick.ego, other).let { ttc ->
              ttc >= TTC_CRITICAL_SECONDS && ttc < TTC_WARNING_SECONDS
            }
      }
    }

// ── Lateral conflict corridor predicates ─────────────────────────────────────

/**
 * Predicate that checks if any vehicle on the left-adjacent lane falls within the lateral conflict
 * corridor, i.e., its position is within [ego.pos - [CORRIDOR_REAR_METERS],
 * ego.pos + [CORRIDOR_FRONT_METERS]].
 *
 * Captures vehicles that would make a left-lane change immediately dangerous, regardless of their
 * speed relative to ego.
 */
val hasCriticalLateralConflictLeft =
    predicate<TimeStep>("hasCriticalLateralConflictLeft") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isOnLeftLaneOf.holds(tick, other to tick.ego) &&
            other.positionOnLaneMeters >= tick.ego.positionOnLaneMeters - CORRIDOR_REAR_METERS &&
            other.positionOnLaneMeters <= tick.ego.positionOnLaneMeters + CORRIDOR_FRONT_METERS
      }
    }

/**
 * Predicate that checks if any vehicle on the right-adjacent lane falls within the lateral conflict
 * corridor.
 *
 * Captures vehicles that would make a right-lane change immediately dangerous.
 */
val hasCriticalLateralConflictRight =
    predicate<TimeStep>("hasCriticalLateralConflictRight") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isOnRightLaneOf.holds(tick, other to tick.ego) &&
            other.positionOnLaneMeters >= tick.ego.positionOnLaneMeters - CORRIDOR_REAR_METERS &&
            other.positionOnLaneMeters <= tick.ego.positionOnLaneMeters + CORRIDOR_FRONT_METERS
      }
    }

// ── Adjacent-lane front TTC predicates ───────────────────────────────────────

/**
 * Predicate that checks if any vehicle ahead on the left-adjacent lane is within a critical TTC.
 *
 * Represents the situation: if ego were to merge left now, it would face a critical rear-end
 * situation with that vehicle.
 */
val hasCriticalTTCFrontLeftLane =
    predicate<TimeStep>("hasCriticalTTCFrontLeftLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isInFrontOnLeftLane.holds(tick, other to tick.ego) &&
            ttcFront(tick.ego, other) < TTC_CRITICAL_SECONDS
      }
    }

/**
 * Predicate that checks if any vehicle ahead on the right-adjacent lane is within a critical TTC.
 *
 * Represents the situation: if ego were to merge right now, it would face a critical rear-end
 * situation with that vehicle.
 */
val hasCriticalTTCFrontRightLane =
    predicate<TimeStep>("hasCriticalTTCFrontRightLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isInFrontOnRightLane.holds(tick, other to tick.ego) &&
            ttcFront(tick.ego, other) < TTC_CRITICAL_SECONDS
      }
    }

// ── Adjacent-lane rear TTC predicates ────────────────────────────────────────

/**
 * Predicate that checks if any vehicle behind on the left-adjacent lane is closing on ego within
 * [TTC_CRITICAL_SECONDS].
 *
 * A fast-closing left-lane follower is relevant for the ego's lateral safety and for detecting
 * situations where the ego is blocking the left lane.
 */
val hasCriticalTTCRearLeftLane =
    predicate<TimeStep>("hasCriticalTTCRearLeftLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isBehindOnLeftLane.holds(tick, other to tick.ego) &&
            ttcRear(tick.ego, other) < TTC_CRITICAL_SECONDS
      }
    }

/**
 * Predicate that checks if any vehicle behind on the right-adjacent lane is closing on ego within
 * [TTC_CRITICAL_SECONDS].
 */
val hasCriticalTTCRearRightLane =
    predicate<TimeStep>("hasCriticalTTCRearRightLane") { tick ->
      exists(tick.vehiclesInTick) { other ->
        isBehindOnRightLane.holds(tick, other to tick.ego) &&
            ttcRear(tick.ego, other) < TTC_CRITICAL_SECONDS
      }
    }

// ── Far-lane corridor predicate ───────────────────────────────────────────────

/**
 * Predicate that checks if any vehicle on the lane two positions away from ego (the far lane) falls
 * within the lateral conflict corridor.
 *
 * Only meaningful when ego is on the leftmost ([LANE_INDEX_LEFT]) or rightmost ([LANE_INDEX_RIGHT])
 * lane. Returns false for the middle lane, as no far lane exists in the three-lane highway model.
 * Uses presence-only detection (no TTC), since the interaction is one lane change removed from
 * being immediate.
 */
val hasFarLaneVehicleInCorridor =
    predicate<TimeStep>("hasFarLaneVehicleInCorridor") { tick ->
      val farLaneIndex =
          when (tick.ego.currentLane.laneIndex) {
            LANE_INDEX_LEFT -> LANE_INDEX_RIGHT
            LANE_INDEX_RIGHT -> LANE_INDEX_LEFT
            else -> return@predicate false
          }
      exists(tick.vehiclesInTick) { other ->
        other.currentLane.laneIndex == farLaneIndex &&
            other.positionOnLaneMeters >= tick.ego.positionOnLaneMeters - CORRIDOR_REAR_METERS &&
            other.positionOnLaneMeters <= tick.ego.positionOnLaneMeters + CORRIDOR_FRONT_METERS
      }
    }
