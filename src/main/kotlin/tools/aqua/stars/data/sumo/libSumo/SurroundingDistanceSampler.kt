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
 * Samples bumper-to-bumper longitudinal distances from [egoId] to the nearest vehicle in each of
 * the six surrounding cells (front, rear, front-left, front-right, rear-left, rear-right).
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
 * - `gapAhead = veh_rear − ego_front` (≥ 0 when vehicle is fully ahead of ego)
 * - `gapBehind = ego_rear − veh_front` (≥ 0 when vehicle is fully behind ego)
 * - Both negative → bounding boxes **overlap** longitudinally (ignored — overlap is a collision
 *   state and not tracked as a distinct cell).
 *
 * ## Cell assignment (adjacent-lane vehicles only)
 * | condition                            | cell                             | distance    |
 * |--------------------------------------|----------------------------------|-------------|
 * | `gapAhead ≥ 0`                       | **front-left** / **front-right** | `gapAhead`  |
 * | `gapBehind ≥ 0` (and `gapAhead < 0`) | **rear-left** / **rear-right**   | `gapBehind` |
 * | both negative (overlap)              | front or rear cell (see below)   | `0`         |
 *
 * When both gaps are negative the bounding boxes overlap longitudinally (vehicle is directly beside
 * ego). Distance is recorded as `0`. The cell is determined by which gap is closer to zero:
 * `gapBehind ≥ gapAhead` → rear cell; otherwise → front cell.
 *
 * The nearest vehicle (smallest distance) wins each cell. There is no fixed zone threshold: a
 * vehicle 0.1 m ahead on the left lane occupies the front-left cell.
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
          snapFront =
              nearer(snapFront, NeighborSnapshot(gapAhead, vehSpeed, vehFront, vehRear, vehAccel))
      else if (gapBehind >= 0.0)
          snapRear =
              nearer(snapRear, NeighborSnapshot(gapBehind, vehSpeed, vehFront, vehRear, vehAccel))
      // Both negative = same-lane collision; ignore for distance purposes.
    } else {
      // ── Adjacent lane: assign to front or rear cell; ignore longitudinal overlaps ─────────────
      val isLeft = laneDiff == 1 // SUMO: higher index = left

      when {
        gapAhead >= 0.0 -> {
          // Vehicle is (at least partially) ahead of ego on the adjacent lane.
          val snap = NeighborSnapshot(gapAhead, vehSpeed, vehFront, vehRear, vehAccel)
          if (isLeft) snapFrontLeft = nearer(snapFrontLeft, snap)
          else snapFrontRight = nearer(snapFrontRight, snap)
        }
        gapBehind >= 0.0 -> {
          // Vehicle is (at least partially) behind ego on the adjacent lane.
          val snap = NeighborSnapshot(gapBehind, vehSpeed, vehFront, vehRear, vehAccel)
          if (isLeft) snapRearLeft = nearer(snapRearLeft, snap)
          else snapRearRight = nearer(snapRearRight, snap)
        }
        else -> {
          // Both gaps negative: bounding boxes overlap longitudinally (vehicle is directly beside
          // ego). Distance is 0. Assign to the cell whose gap is closer to zero — i.e. whichever
          // end of the ego the vehicle is nearer to determines front vs rear.
          val snap = NeighborSnapshot(0.0, vehSpeed, vehFront, vehRear, vehAccel)
          if (gapBehind >= gapAhead) {
            // Vehicle centre is behind ego centre → rear cell.
            if (isLeft) snapRearLeft = nearer(snapRearLeft, snap)
            else snapRearRight = nearer(snapRearRight, snap)
          } else {
            // Vehicle centre is ahead of ego centre → front cell.
            if (isLeft) snapFrontLeft = nearer(snapFrontLeft, snap)
            else snapFrontRight = nearer(snapFrontRight, snap)
          }
        }
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
