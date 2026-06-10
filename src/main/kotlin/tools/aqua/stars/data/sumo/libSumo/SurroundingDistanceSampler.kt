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

package tools.aqua.stars.data.sumo.libSumo

import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.SurroundingVehicleDistances

/**
 * Longitudinal gap (m) from the ego's bounding box within which a vehicle on an adjacent lane is
 * considered to be *beside* the ego (middle row of the 3×3 grid) rather than ahead or behind it.
 *
 * ```
 * ┌────────────┬──────────┬─────────────┐
 * │ front-left │  front   │ front-right │
 * ├────────────┼──────────┼─────────────┤
 * │    left    │   ego    │    right    │  ← "besides" zone (gap ≤ this constant)
 * ├────────────┼──────────┼─────────────┤
 * │  rear-left │   rear   │  rear-right │
 * └────────────┴──────────┴─────────────┘
 * ```
 */
const val VEHICLE_BESIDES_MAX_DISTANCE_METERS = 3.0

/**
 * Samples bumper-to-bumper longitudinal distances from [egoId] to the nearest vehicle in each cell
 * of the surrounding 3×3 grid.
 *
 * ## Bounding-box distance
 * Every vehicle's longitudinal extent is computed from the SUMO lane position (= front bumper) and
 * vehicle length:
 * ```
 *   front bumper = getLanePosition()
 *   rear  bumper = getLanePosition() − getLength()
 * ```
 *
 * The gap between two bounding boxes is:
 * - `gapAhead = veh_rear − ego_front` (≥ 0 when vehicle is fully ahead)
 * - `gapBehind = ego_rear − veh_front` (≥ 0 when vehicle is fully behind)
 * - Both negative → bounding boxes **overlap** longitudinally.
 *
 * ## Cell assignment (adjacent-lane vehicles only)
 * The bounding-box distance is `min(gapAhead, gapBehind)` clamped to 0 for overlaps. | box distance
 * | direction | cell | |---|---|---| | ≤ [VEHICLE_BESIDES_MAX_DISTANCE_METERS] | – | **left** /
 * **right** (distance = box dist; 0 for overlap) | | > threshold | ahead | **front-left** /
 * **front-right** (distance = gapAhead) | | > threshold | behind | **rear-left** / **rear-right**
 * (distance = gapBehind) |
 *
 * ## Scope
 * Only vehicles on the same road edge and an immediately adjacent lane (`|laneIndex diff| ≤ 1`) are
 * considered. SUMO lane indices increase toward the left (`0` = rightmost lane).
 */
fun sampleEgoSurroundingDistances(egoId: String): SurroundingVehicleDistances {
  val egoFront = SumoVehicle.getLanePosition(egoId)
  val egoLength = runCatching { SumoVehicle.getLength(egoId) }.getOrElse { 5.0 }
  val egoRear = egoFront - egoLength
  val egoLane = SumoVehicle.getLaneIndex(egoId)
  val egoRoad = SumoVehicle.getRoadID(egoId)

  var distFront: Double? = null
  var distRear: Double? = null
  var distLeft: Double? = null
  var distRight: Double? = null
  var distFrontLeft: Double? = null
  var distRearLeft: Double? = null
  var distFrontRight: Double? = null
  var distRearRight: Double? = null

  for (vehId in SumoVehicle.getIDList()) {
    if (vehId == egoId) continue
    if (SumoVehicle.getRoadID(vehId) != egoRoad) continue

    val laneDiff = SumoVehicle.getLaneIndex(vehId) - egoLane
    if (laneDiff !in -1..1) continue

    val vehFront = SumoVehicle.getLanePosition(vehId)
    val vehLength = runCatching { SumoVehicle.getLength(vehId) }.getOrElse { 5.0 }
    val vehRear = vehFront - vehLength

    // Positive when the vehicle is fully ahead / fully behind; negative when boxes overlap.
    val gapAhead = vehRear - egoFront
    val gapBehind = egoRear - vehFront

    if (laneDiff == 0) {
      // ── Same lane: only front / rear ──────────────────────────────────────────────────────────
      if (gapAhead >= 0.0) distFront = nearerGap(distFront, gapAhead)
      else if (gapBehind >= 0.0) distRear = nearerGap(distRear, gapBehind)
      // Both negative = same-lane collision; ignore for distance purposes.
    } else {
      // ── Adjacent lane: assign to besides-zone or corner cell ──────────────────────────────────
      val isLeft = laneDiff == 1 // SUMO: higher index = left

      // Bounding-box distance (0 when boxes would overlap if on the same lane).
      val boxDist: Double =
          when {
            gapAhead >= 0.0 -> gapAhead
            gapBehind >= 0.0 -> gapBehind
            else -> 0.0 // longitudinal overlap → beside ego
          }

      if (boxDist <= VEHICLE_BESIDES_MAX_DISTANCE_METERS) {
        // Vehicle is in the "besides" zone (middle row of the 3×3 grid).
        if (isLeft) distLeft = nearerGap(distLeft, boxDist)
        else distRight = nearerGap(distRight, boxDist)
      } else if (gapAhead > VEHICLE_BESIDES_MAX_DISTANCE_METERS) {
        // Vehicle is clearly ahead — corner cell.
        if (isLeft) distFrontLeft = nearerGap(distFrontLeft, gapAhead)
        else distFrontRight = nearerGap(distFrontRight, gapAhead)
      } else {
        // Vehicle is clearly behind — corner cell.
        if (isLeft) distRearLeft = nearerGap(distRearLeft, gapBehind)
        else distRearRight = nearerGap(distRearRight, gapBehind)
      }
    }
  }

  return SurroundingVehicleDistances(
      frontMeters = distFront,
      rearMeters = distRear,
      frontLeftMeters = distFrontLeft,
      frontRightMeters = distFrontRight,
      rearLeftMeters = distRearLeft,
      rearRightMeters = distRearRight,
      leftMeters = distLeft,
      rightMeters = distRight,
  )
}

/** Keeps the smaller of the two gaps (nearer / more dangerous). */
private fun nearerGap(current: Double?, candidate: Double): Double =
    if (current == null || candidate < current) candidate else current
