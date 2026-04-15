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

// ── Constants ────────────────────────────────────────────────────────────────

/** Front TTC below this is considered critical (seconds). */
private const val TTC_CRITICAL_S = 3.0

/** Front TTC below this is considered a warning (seconds). */
private const val TTC_WARNING_S = 6.0

/** Rear extent of the lateral conflict corridor relative to ego front bumper (m). */
private const val CORRIDOR_REAR_M = 20.0

/** Forward extent of the lateral conflict corridor relative to ego front bumper (m). */
private const val CORRIDOR_FRONT_M = 40.0

/** Maximum range considered for TTC computations (m). */
private const val TTC_MAX_RANGE_M = 100.0

// ── TTC helper functions ─────────────────────────────────────────────────────

/**
 * TTC for [ego] approaching a vehicle [other] ahead on any lane. Returns infinity when not closing
 * or [other] is not in front.
 */
private fun ttcFront(ego: Vehicle, other: Vehicle): Double {
  val gap = other.positionOnLaneMeters.toDouble() - ego.positionOnLaneMeters
  val closingSpeed = ego.speedKmPerHour - other.speedKmPerHour
  return if (gap > 0.0 && closingSpeed > 0.0) gap / closingSpeed else Double.POSITIVE_INFINITY
}

/** TTC for a vehicle [other] approaching [ego] from behind. Returns infinity when not closing. */
private fun ttcRear(ego: Vehicle, other: Vehicle): Double {
  val gap = ego.positionOnLaneMeters.toDouble() - other.positionOnLaneMeters
  val closingSpeed = other.speedKmPerHour - ego.speedKmPerHour
  return if (gap > 0.0 && closingSpeed > 0.0) gap / closingSpeed else Double.POSITIVE_INFINITY
}

// ── Same-lane front TTC ───────────────────────────────────────────────────────

val hasCriticalTTCFrontSameLane = predicate<TimeStep>("hasCriticalTTCFrontSameLane") { true }

val hasWarningTTCFrontSameLane = predicate<TimeStep>("") { true }

// ── Lateral conflict corridor ─────────────────────────────────────────────────
// A vehicle is in the corridor if it is on an adjacent lane and within
// [ego.positionOnLaneMeters - CORRIDOR_REAR_M, ego.positionOnLaneMeters + CORRIDOR_FRONT_M].
// This captures vehicles that make a lane change dangerous regardless of
// their exact speed relative to ego.

val hasCriticalLateralConflictLeft = predicate<TimeStep>("") { true }

val hasCriticalLateralConflictRight = predicate<TimeStep>("") { true }

// ── Adjacent-lane front TTC ───────────────────────────────────────────────────
// Represents: if ego were to merge left/right right now, would it be in a
// critical situation with the vehicle ahead on that lane?

val hasCriticalTTCFrontLeftLane = predicate<TimeStep>("") { true }

val hasCriticalTTCFrontRightLane = predicate<TimeStep>("") { true }

// ── Adjacent-lane rear TTC ────────────────────────────────────────────────────
// A fast-approaching vehicle from behind on an adjacent lane is relevant
// both for safe distance and for the ego's lane-change decisions.

val hasCriticalTTCRearLeftLane = predicate<TimeStep>("") { true }

val hasCriticalTTCRearRightLane = predicate<TimeStep>("") { true }
