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

import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridTrafficScenarioGenerator

/**
 * One vehicle to be placed when reconstructing a recorded tick.
 *
 * @property vehId SUMO vehicle id to use.
 * @property laneIndex SUMO lane index (0=right .. 2=left).
 * @property positionMeters Lane position (front bumper) to force-place the vehicle at.
 * @property speedMps Exact recorded speed to pin the vehicle to after placement.
 * @property isEgo Whether this placement is the ego vehicle.
 */
data class ReplayPlacement(
    val vehId: String,
    val laneIndex: Int,
    val positionMeters: Double,
    val speedMps: Double,
    val isEgo: Boolean,
)

/**
 * Anchor lane-position (m) used to place the ego vehicle when reconstructing a tick. Reuses
 * [GridTrafficScenarioGenerator]'s default middle-row interval center — an already-proven-safe
 * position on the network — instead of inventing a new magic number.
 */
val REPLAY_EGO_ANCHOR_METERS: Double =
    GridTrafficScenarioGenerator().let { (it.i1Start + it.i1End) / 2.0 }.toDouble()

/**
 * Inverts [sampleEgoSurroundingDistances]'s gap arithmetic to reconstruct absolute SUMO placements
 * for the ego vehicle and each present neighbour of [tick], anchoring the ego at [egoAnchorMeters].
 *
 * Only *relative* values from [tick] are used for positioning: bumper-to-bumper distances
 * (`surroundingDist*`) and, for a neighbour's length, the *difference* of its two absolute
 * bumper-position columns (a length, not a location). The absolute lane-position columns themselves
 * (`egoFrontBumperPosMeters`, `surrounding*FrontBumperPosMeters`, etc.) are never used as placement
 * coordinates — only their pairwise differences.
 *
 * A cell is skipped (no placement produced) when its `surroundingDist*` value is `null` (no vehicle
 * recorded in that cell).
 *
 * @param tick The recorded tick to reconstruct.
 * @param egoAnchorMeters Lane position to place the ego vehicle at.
 * @return Placements for the ego vehicle plus every present neighbour.
 */
fun computeReplayPlacements(
    tick: MetricFailedMonitorsEntry,
    egoAnchorMeters: Double = REPLAY_EGO_ANCHOR_METERS,
): List<ReplayPlacement> {
  val egoLaneIndex = checkNotNull(tick.egoLane) { "Tick ${tick.id} has no egoLane" }.ordinal
  val egoSpeedMps =
      checkNotNull(tick.egoSpeedMps) { "Tick ${tick.id} has no egoSpeedMps" }.toDouble()
  val egoLengthMeters =
      requireNotNull(tick.egoFrontBumperPosMeters) {
        "Tick ${tick.id} has no egoFrontBumperPosMeters"
      } -
          requireNotNull(tick.egoBackBumperPosMeters) {
            "Tick ${tick.id} has no egoBackBumperPosMeters"
          }

  val placements = mutableListOf<ReplayPlacement>()
  placements +=
      ReplayPlacement(
          vehId = "veh_replay_ego_${tick.id}",
          laneIndex = egoLaneIndex,
          positionMeters = egoAnchorMeters,
          speedMps = egoSpeedMps,
          isEgo = true)

  fun neighbourLength(cellName: String, front: Float?, back: Float?): Double =
      (requireNotNull(front) { "Tick ${tick.id} $cellName missing front bumper" } -
              requireNotNull(back) { "Tick ${tick.id} $cellName missing back bumper" })
          .toDouble()

  fun addAhead(
      cellName: String,
      dist: Float?,
      speed: Float?,
      front: Float?,
      back: Float?,
      laneIndex: Int
  ) {
    if (dist == null) return
    check(laneIndex in 0..2) { "Tick ${tick.id} $cellName resolves to invalid lane $laneIndex" }
    val length = neighbourLength(cellName, front, back)
    placements +=
        ReplayPlacement(
            vehId = "veh_replay_${cellName}_${tick.id}",
            laneIndex = laneIndex,
            positionMeters = egoAnchorMeters + dist + length,
            speedMps =
                requireNotNull(speed) { "Tick ${tick.id} $cellName missing speed" }.toDouble(),
            isEgo = false)
  }

  fun addBehind(
      cellName: String,
      dist: Float?,
      speed: Float?,
      front: Float?,
      back: Float?,
      laneIndex: Int
  ) {
    if (dist == null) return
    check(laneIndex in 0..2) { "Tick ${tick.id} $cellName resolves to invalid lane $laneIndex" }
    // Length is not needed for rear-side placement: SUMO lane position is the front bumper, and
    // `egoAnchorMeters - egoLengthMeters - dist` already lands exactly on the neighbour's front
    // bumper (egoRear - dist = vehFront, per SurroundingDistanceSampler's gapBehind definition).
    placements +=
        ReplayPlacement(
            vehId = "veh_replay_${cellName}_${tick.id}",
            laneIndex = laneIndex,
            positionMeters = egoAnchorMeters - egoLengthMeters - dist,
            speedMps =
                requireNotNull(speed) { "Tick ${tick.id} $cellName missing speed" }.toDouble(),
            isEgo = false)
  }

  addAhead(
      "front",
      tick.surroundingDistFront,
      tick.surroundingFrontSpeedMps,
      tick.surroundingFrontFrontBumperPosMeters,
      tick.surroundingFrontBackBumperPosMeters,
      egoLaneIndex)
  addBehind(
      "rear",
      tick.surroundingDistRear,
      tick.surroundingRearSpeedMps,
      tick.surroundingRearFrontBumperPosMeters,
      tick.surroundingRearBackBumperPosMeters,
      egoLaneIndex)
  addAhead(
      "front_left",
      tick.surroundingDistFrontLeft,
      tick.surroundingFrontLeftSpeedMps,
      tick.surroundingFrontLeftFrontBumperPosMeters,
      tick.surroundingFrontLeftBackBumperPosMeters,
      egoLaneIndex + 1)
  addAhead(
      "front_right",
      tick.surroundingDistFrontRight,
      tick.surroundingFrontRightSpeedMps,
      tick.surroundingFrontRightFrontBumperPosMeters,
      tick.surroundingFrontRightBackBumperPosMeters,
      egoLaneIndex - 1)
  addBehind(
      "rear_left",
      tick.surroundingDistRearLeft,
      tick.surroundingRearLeftSpeedMps,
      tick.surroundingRearLeftFrontBumperPosMeters,
      tick.surroundingRearLeftBackBumperPosMeters,
      egoLaneIndex + 1)
  addBehind(
      "rear_right",
      tick.surroundingDistRearRight,
      tick.surroundingRearRightSpeedMps,
      tick.surroundingRearRightFrontBumperPosMeters,
      tick.surroundingRearRightBackBumperPosMeters,
      egoLaneIndex - 1)

  return placements
}
