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

  var snapFront: NeighborSnapshot? = null
  var snapRear: NeighborSnapshot? = null
  var snapLeft: NeighborSnapshot? = null
  var snapRight: NeighborSnapshot? = null
  var snapFrontLeft: NeighborSnapshot? = null
  var snapRearLeft: NeighborSnapshot? = null
  var snapFrontRight: NeighborSnapshot? = null
  var snapRearRight: NeighborSnapshot? = null

  for (vehId in SumoVehicle.getIDList()) {
    if (vehId == egoId) continue
    if (SumoVehicle.getRoadID(vehId) != egoRoad) continue

    val laneDiff = SumoVehicle.getLaneIndex(vehId) - egoLane
    if (laneDiff !in -1..1) continue

    val vehFront = SumoVehicle.getLanePosition(vehId)
    val vehLength = runCatching { SumoVehicle.getLength(vehId) }.getOrElse { 5.0 }
    val vehRear = vehFront - vehLength
    val vehSpeed = SumoVehicle.getSpeed(vehId)
    val vehAccel = runCatching { SumoVehicle.getAcceleration(vehId) }.getOrElse { 0.0 }

    // Positive when the vehicle is fully ahead / fully behind; negative when boxes overlap.
    val gapAhead = vehRear - egoFront
    val gapBehind = egoRear - vehFront

    if (laneDiff == 0) {
      // ── Same lane: only front / rear ──────────────────────────────────────────────────────────
      if (gapAhead >= 0.0)
          snapFront = nearer(snapFront, NeighborSnapshot(gapAhead, vehSpeed, vehFront, vehRear, vehAccel))
      else if (gapBehind >= 0.0)
          snapRear = nearer(snapRear, NeighborSnapshot(gapBehind, vehSpeed, vehFront, vehRear, vehAccel))
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
        val snap = NeighborSnapshot(boxDist, vehSpeed, vehFront, vehRear, vehAccel)
        if (isLeft) snapLeft = nearer(snapLeft, snap) else snapRight = nearer(snapRight, snap)
      } else if (gapAhead > VEHICLE_BESIDES_MAX_DISTANCE_METERS) {
        // Vehicle is clearly ahead — corner cell.
        val snap = NeighborSnapshot(gapAhead, vehSpeed, vehFront, vehRear, vehAccel)
        if (isLeft) snapFrontLeft = nearer(snapFrontLeft, snap)
        else snapFrontRight = nearer(snapFrontRight, snap)
      } else {
        // Vehicle is clearly behind — corner cell.
        val snap = NeighborSnapshot(gapBehind, vehSpeed, vehFront, vehRear, vehAccel)
        if (isLeft) snapRearLeft = nearer(snapRearLeft, snap)
        else snapRearRight = nearer(snapRearRight, snap)
      }
    }
  }

  return SurroundingVehicleDistances(
      frontMeters = snapFront?.distMeters,
      rearMeters = snapRear?.distMeters,
      frontLeftMeters = snapFrontLeft?.distMeters,
      frontRightMeters = snapFrontRight?.distMeters,
      rearLeftMeters = snapRearLeft?.distMeters,
      rearRightMeters = snapRearRight?.distMeters,
      leftMeters = snapLeft?.distMeters,
      rightMeters = snapRight?.distMeters,
      frontSpeedMps = snapFront?.speedMps,
      frontFrontBumperPositionMeters = snapFront?.frontBumperPositionMeters,
      frontBackBumperPositionMeters = snapFront?.backBumperPositionMeters,
      frontAccelMps2 = snapFront?.accelMps2,
      rearSpeedMps = snapRear?.speedMps,
      rearFrontBumperPositionMeters = snapRear?.frontBumperPositionMeters,
      rearBackBumperPositionMeters = snapRear?.backBumperPositionMeters,
      rearAccelMps2 = snapRear?.accelMps2,
      frontLeftSpeedMps = snapFrontLeft?.speedMps,
      frontLeftFrontBumperPositionMeters = snapFrontLeft?.frontBumperPositionMeters,
      frontLeftBackBumperPositionMeters = snapFrontLeft?.backBumperPositionMeters,
      frontLeftAccelMps2 = snapFrontLeft?.accelMps2,
      frontRightSpeedMps = snapFrontRight?.speedMps,
      frontRightFrontBumperPositionMeters = snapFrontRight?.frontBumperPositionMeters,
      frontRightBackBumperPositionMeters = snapFrontRight?.backBumperPositionMeters,
      frontRightAccelMps2 = snapFrontRight?.accelMps2,
      rearLeftSpeedMps = snapRearLeft?.speedMps,
      rearLeftFrontBumperPositionMeters = snapRearLeft?.frontBumperPositionMeters,
      rearLeftBackBumperPositionMeters = snapRearLeft?.backBumperPositionMeters,
      rearLeftAccelMps2 = snapRearLeft?.accelMps2,
      rearRightSpeedMps = snapRearRight?.speedMps,
      rearRightFrontBumperPositionMeters = snapRearRight?.frontBumperPositionMeters,
      rearRightBackBumperPositionMeters = snapRearRight?.backBumperPositionMeters,
      rearRightAccelMps2 = snapRearRight?.accelMps2,
      leftSpeedMps = snapLeft?.speedMps,
      leftFrontBumperPositionMeters = snapLeft?.frontBumperPositionMeters,
      leftBackBumperPositionMeters = snapLeft?.backBumperPositionMeters,
      leftAccelMps2 = snapLeft?.accelMps2,
      rightSpeedMps = snapRight?.speedMps,
      rightFrontBumperPositionMeters = snapRight?.frontBumperPositionMeters,
      rightBackBumperPositionMeters = snapRight?.backBumperPositionMeters,
      rightAccelMps2 = snapRight?.accelMps2,
  )
}

/** Snapshot of the nearest neighbour vehicle in one grid cell. */
private data class NeighborSnapshot(
    val distMeters: Double,
    val speedMps: Double,
    val frontBumperPositionMeters: Double,
    val backBumperPositionMeters: Double,
    val accelMps2: Double,
)

/** Keeps the snapshot with the smaller distance (nearer / more dangerous). */
private fun nearer(current: NeighborSnapshot?, candidate: NeighborSnapshot): NeighborSnapshot =
    if (current == null || candidate.distMeters < current.distMeters) candidate else current
