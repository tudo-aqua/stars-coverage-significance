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
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle
import tools.aqua.stars.logic.kcmftbl.firstorder.exists

/** TTC in seconds below which a front approach is classified as critical. */
const val TTC_CRITICAL_SECONDS: Double = 4.0

const val VEHICLE_TIME_GAP_SECONDS = 10.0

const val LEFT_LANE_INDEX_MODIFIER = +1

const val RIGHT_LANE_INDEX_MODIFIER = -1

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

val hasVehicleBehindInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle Behind in Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex == tick.ego.currentLane.laneIndex &&
            isInTimeGapTo.holds(tick, otherVehicle to tick.ego)
      }
    }

val isInTimeGapTo =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Is in Time Gap to") { _, vehiclePair ->
      val vehicleBehind = vehiclePair.first
      val vehicleFront = vehiclePair.second
      val distanceBetween =
          vehicleFront.backBumperPositionOnLaneMeters -
              vehicleBehind.frontBumperPositionOnLaneMeters
      val timeDistance = distanceBetween / vehicleBehind.speedMetersPerSecond
      timeDistance <= VEHICLE_TIME_GAP_SECONDS
    }

val hasCriticalTTCWithVehicleBehind =
    predicate<TimeStep>("Has Critical TTC From Vehicle Behind") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex == tick.ego.currentLane.laneIndex &&
            ttcRear(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }

val hasVehicleInRightLaneInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle in Right Lane in Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + RIGHT_LANE_INDEX_MODIFIER &&
            isInTimeGapTo.holds(tick, otherVehicle to tick.ego)
      }
    }

val hasCriticalTTCWithVehicleInRightLane =
    predicate<TimeStep>("Has Critical TTC with Vehicle in Right Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + RIGHT_LANE_INDEX_MODIFIER &&
            ttcRear(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }

val hasNoVehicleInRightLaneInRelevantTimeGap =
    predicate<TimeStep>("Has No Vehicle in Right Lane in Relevant Time Gap") { tick ->
      !hasVehicleInRightLaneInRelevantTimeGap.holds(tick)
    }

val hasCriticalTTCWithVehicleInRightLaneOfRightLane =
    predicate<TimeStep>("Has Critical TTC with Vehicle in Right Lane of Right Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * RIGHT_LANE_INDEX_MODIFIER) &&
            ttcRear(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }

val hasVehicleInRightLaneOfRightLaneInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle in Right Lane of Right Lane In Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * RIGHT_LANE_INDEX_MODIFIER) &&
            isInTimeGapTo.holds(tick, otherVehicle to tick.ego)
      }
    }

val hasVehicleInFrontInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle in Front in Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex == tick.ego.currentLane.laneIndex &&
            isInTimeGapTo.holds(tick, tick.ego to otherVehicle)
      }
    }

val hasCriticalTTCWithVehicleInFront =
    predicate<TimeStep>("Has Critical TTC with Vehicle in Front") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex == tick.ego.currentLane.laneIndex &&
            ttcFront(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }

val hasVehicleInLeftLaneInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle in Left Lane in Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + LEFT_LANE_INDEX_MODIFIER &&
            isInTimeGapTo.holds(tick, tick.ego to otherVehicle)
      }
    }

val hasCriticalTTCWithVehicleInLeftLane =
    predicate<TimeStep>("Has Critical TTC with Vehicle in Left Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + LEFT_LANE_INDEX_MODIFIER &&
            ttcFront(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }

val hasNoVehicleInLeftLaneInRelevantTimeGap =
    predicate<TimeStep>("Has No Vehicle in Left Lane in Relevant Time Gap") { tick ->
      !hasVehicleInLeftLaneInRelevantTimeGap.holds(tick)
    }

val hasVehicleInLeftLaneOfLeftLaneInRelevantTimeGap =
    predicate<TimeStep>("Has Vehicle in Left Lane of Left Lane in Relevant Time Gap") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * LEFT_LANE_INDEX_MODIFIER) &&
            isInTimeGapTo.holds(tick, tick.ego to otherVehicle)
      }
    }

val hasCriticalTTCWithVehicleInLeftLaneOfLeftLane =
    predicate<TimeStep>("Has Critical TTC with Vehicle in Left Lane of Left Lane") { tick ->
      exists(tick.nonEgoVehicles) { otherVehicle ->
        otherVehicle.currentLane.laneIndex ==
            tick.ego.currentLane.laneIndex + (2 * LEFT_LANE_INDEX_MODIFIER) &&
            ttcFront(tick.ego, otherVehicle) < TTC_CRITICAL_SECONDS
      }
    }
