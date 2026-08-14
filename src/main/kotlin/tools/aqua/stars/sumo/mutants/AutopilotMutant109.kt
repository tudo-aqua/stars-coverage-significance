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

package tools.aqua.stars.sumo.mutants

import kotlin.math.sqrt
import org.eclipse.sumo.libsumo.StringDoublePair
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.sumo.LaneChangeDirection
import tools.aqua.stars.sumo.Mutant
import tools.aqua.stars.sumo.MutantManeuver

/** Simple AutopilotMutant109 with ACC and Lane Change behavior. Extends [Mutant]. */
class AutopilotMutant109 : Mutant() {

  // -------------------- ACC parameters --------------------
  /** The cruise speed in meters per second. */
  var cruiseSpeedInMps = 27.77
  /** The time headway to the leader in seconds. */
  var timeHeadwayToLeaderInSeconds = 1.0
  /** The minimum gap to the leading vehicle in meters. */
  var minGapToLeadingInMeters = 2.5

  /** Maximum longitudinal acceleration [m/s²]. */
  var maxAccelerationInMps2 = 2.6
  /** The maximum deceleration in meters per second squared. */
  var maxDecelerationInMps2 = 4.5
  /** The step length in seconds. */
  var stepLengthSeconds = 0.1

  /** Assumed maximum deceleration of the leader [m/s²] for kinematic safety checks. */
  var leaderMaxDecelerationInMps2 = 9.0

  /** Gain applied to the gap error term (`gap - desiredGap`) when computing ACC target speed. */
  var gapGain = 0.5

  /** Gain applied to the relative speed term (`vLeader - vEgo`) when computing ACC target speed. */
  var relativeSpeedGain = 0.2
  /**
   * If `gap < hardBrakeGapFactor * desiredGap`, apply an additional penalty to reduce target speed.
   */
  var hardBrakeGapFactor = 0.6
  /** The minimum target speed in meters per second. */
  var minTargetSpeedMps = 0.0

  // -------------------- Lane change parameters --------------------
  /**
   * The minimum gain in meters per second for lane change. If the gain is below this threshold,
   * lane change will be postponed.
   */
  var laneChangeMinGainInMps = 1.0

  /** The distance ahead to look for a neighbor. */
  var neighborLookAheadInMeters = 150.0

  /** The distance ahead to look for the leader. */
  var leaderLookAheadInMeters = 200.0

  /** Required free distance ahead on all lanes on the chosen side before changing lanes. */
  var laneChangeSideFrontGapInMeters = 20.0

  /** Required free distance behind on all lanes on the chosen side before changing lanes. */
  var laneChangeSideBackGapInMeters = 15.0

  /**
   * Whether to prefer a left lane change. If true, the ego vehicle will prefer left lane change. If
   * false, the ego vehicle will prefer a right lane change.
   */
  var preferLeftLane = true

  /**
   * The factor for determining if the ego vehicle is stuck. If the gap to the leader is less than
   * this factor times the desired gap, and the leader is slower than the ego vehicle, the ego
   * vehicle will consider lane change.
   */
  var stuckGapFactor = 1.0

  /**
   * The speed delta for determining if the ego vehicle is stuck. If the leader is slower than the
   * ego vehicle by this amount, the ego vehicle will consider a lane change.
   */
  var stuckSpeedDeltaMps = 0.5

  /** Weight for the side-lane speed-gain term in lane-change scoring. */
  var sideLeaderWeight = 1.0

  /**
   * Duration (s) parameter passed to changeLane (how long the lane-change request should be kept).
   */
  var maxLaneChangeDurationInSeconds = 1.0

  // -------------------- Public tick --------------------
  override fun controlTick(egoId: String): MutantManeuver {
    val vEgo = SumoVehicle.getSpeed(egoId)
    val leader = leaderInfoOrNull(egoId)

    val desiredGap = desiredGapMeters(vEgo)

    val vTarget = desiredSpeedAcc(vEgo, desiredGap, leader)
    val vCmd = clampSpeedWithAccelLimits(vEgo, vTarget, stepLengthSeconds)

    SumoVehicle.setSpeed(egoId, vCmd)

    val laneChangeDir = maybeLaneChange(egoId, vEgo, desiredGap, leader)
    return MutantManeuver(vCmd, laneChangeDir)
  }

  // -------------------- ACC --------------------
  private fun leaderInfoOrNull(egoId: String): StringDoublePair? =
      runCatching { SumoVehicle.getLeader(egoId, leaderLookAheadInMeters) }
          .getOrNull()
          ?.takeIf { it.first.isNotEmpty() }

  private fun desiredGapMeters(vEgo: Double): Double =

      /**
       * AUTO GENERATED COMMENT Mutation Operator: ArithmeticReplacementOperator Line number: 137
       * Id: d8f2e2ec-3fd7-41ef-9172-9718b0c242b1, Old Operator: *, New Operator: -
       */
      minGapToLeadingInMeters + timeHeadwayToLeaderInSeconds - vEgo

  private fun desiredSpeedAcc(vEgo: Double, desiredGap: Double, leader: StringDoublePair?): Double {
    if (leader == null) return cruiseSpeedInMps

    val leaderId = leader.first
    val gap = leader.second
    val vLeader = SumoVehicle.getSpeed(leaderId)

    val gapError = gap - desiredGap
    val relSpeed = vLeader - vEgo

    // Start with cruising, then restrict downwards.
    var vTarget = cruiseSpeedInMps

    // vLeader + gapGain * gapError + relSpeedGain * relSpeed
    val followProposal = vLeader + gapGain * gapError + relativeSpeedGain * relSpeed
    if (followProposal < vTarget) vTarget = followProposal

    // Extra safety-ish branch: if too close, bias towards braking
    if (gap < hardBrakeGapFactor * desiredGap) {
      val penalty = absVal(gapError) * 0.3
      val hardProposal = vLeader - penalty
      if (hardProposal < vTarget) vTarget = hardProposal
    }

    // Kinematic safety clamp (braking-distance based).
    // This provides a hard upper bound on speed so that, under the assumptions below,
    // the ego can still avoid collision even if the leader brakes strongly.
    val vSafe = safeSpeedKinematic(gapMeters = gap, vLeader = vLeader)
    if (vSafe < vTarget) vTarget = vSafe

    // clamp lower bound
    if (vTarget < minTargetSpeedMps) vTarget = minTargetSpeedMps
    // clamp upper bound (explicit, no min())
    if (vTarget > cruiseSpeedInMps) vTarget = cruiseSpeedInMps

    return vTarget
  }

  /**
   * Computes a kinematic safe speed upper bound using a simple braking-distance inequality.
   *
   * Assumptions:
   * - Ego may continue at current speed for one control step (reaction time = [stepLengthSeconds]).
   * - After that, ego brakes with [maxDecelerationInMps2].
   * - Leader may brake immediately with [leaderMaxDecelerationInMps2].
   * - A standstill gap of [minGapToLeadingInMeters] is preserved.
   *
   * The returned value is an upper bound for the *next* commanded speed.
   */
  private fun safeSpeedKinematic(gapMeters: Double, vLeader: Double): Double {
    val bEgo = if (maxDecelerationInMps2 > 0.0) maxDecelerationInMps2 else 0.0
    if (bEgo <= 1e-9) return 0.0

    val bLead = if (leaderMaxDecelerationInMps2 > 0.0) leaderMaxDecelerationInMps2 else bEgo
    val tau = if (stepLengthSeconds > 0.0) stepLengthSeconds else 0.0

    // Free space available for ego braking (gap minus desired standstill gap).
    val netGap = gapMeters - minGapToLeadingInMeters
    val netGapClamped = if (netGap > 0.0) netGap else 0.0

    // Leader stopping distance under assumed max braking.
    val leaderStopDist = (vLeader * vLeader) / (2.0 * bLead)

    // Total distance ego may spend: net gap + leader stopping distance.
    val sAvail = netGapClamped + leaderStopDist

    // Solve: v*tau + v^2/(2*bEgo) <= sAvail
    // => v <= -bEgo*tau + sqrt((bEgo*tau)^2 + 2*bEgo*sAvail)
    val bt = bEgo * tau
    val disc = bt * bt + 2.0 * bEgo * sAvail
    val root = if (disc > 0.0) sqrt(disc) else 0.0
    val vSafe = root - bt

    return if (vSafe > 0.0) vSafe else 0.0
  }

  private fun clampSpeedWithAccelLimits(vNow: Double, vTarget: Double, dt: Double): Double {
    val dvWanted = vTarget - vNow
    val dvMaxUp = maxAccelerationInMps2 * dt
    val dvMaxDown = -maxDecelerationInMps2 * dt

    val dvApplied =
        if (dvWanted > dvMaxUp) dvMaxUp else if (dvWanted < dvMaxDown) dvMaxDown else dvWanted

    val vNew = vNow + dvApplied
    return if (vNew < minTargetSpeedMps) minTargetSpeedMps else vNew
  }

  private fun absVal(x: Double): Double = if (x < 0.0) -x else x

  // -------------------- Lane change --------------------
  private fun maybeLaneChange(
      egoId: String,
      vEgo: Double,
      desiredGap: Double,
      leader: StringDoublePair?
  ): LaneChangeDirection {
    val baseLaneIndex = SumoVehicle.getLaneIndex(egoId)

    val curLeaderSpeed =
        if (leader != null) SumoVehicle.getSpeed(leader.first) else cruiseSpeedInMps
    val curGap = if (leader != null) leader.second else Double.POSITIVE_INFINITY

    val stuck = isStuck(vEgo, curLeaderSpeed, curGap, desiredGap)

    val left = evaluateLaneChange(egoId, dir = 1, stuck = stuck, curLeaderSpeed = curLeaderSpeed)
    val right =
        evaluateLaneChange(egoId, dir = 0 - 1, stuck = stuck, curLeaderSpeed = curLeaderSpeed)

    val chosenDir = chooseDirection(left, right) ?: return LaneChangeDirection.NO_LANE_CHANGE

    val targetLaneIndex = baseLaneIndex + chosenDir
    if (targetLaneIndex < 0) return LaneChangeDirection.NO_LANE_CHANGE

    SumoVehicle.changeLane(egoId, targetLaneIndex, maxLaneChangeDurationInSeconds)
    return LaneChangeDirection.fromDirection(chosenDir)
  }

  private fun isStuck(vEgo: Double, vLeader: Double, gap: Double, desiredGap: Double): Boolean {
    val tooClose = gap < stuckGapFactor * desiredGap
    val leaderSlower = (vLeader + stuckSpeedDeltaMps) < vEgo
    return tooClose && leaderSlower
  }

  private data class LaneEval(val dir: Int, val feasible: Boolean, val score: Double)

  private data class Neighbor(val id: String, val distM: Double)

  private fun evaluateLaneChange(
      egoId: String,
      dir: Int,
      stuck: Boolean,
      curLeaderSpeed: Double
  ): LaneEval {
    val wantRight = dir < 0
    val wantLeft = dir > 0
    if (!wantLeft && !wantRight) {
      return LaneEval(dir, feasible = false, score = Double.NEGATIVE_INFINITY)
    }

    val targetLaneSafe = isTargetDirectionFree(egoId, dir)
    val sideCorridorSafe = areAllLanesOnSideFree(egoId, dir)
    if (!targetLaneSafe || !sideCorridorSafe) {
      return LaneEval(dir, feasible = false, score = Double.NEGATIVE_INFINITY)
    }

    val sideLeader = getSideLeaderAhead(egoId, dir)
    val vSideLeader =
        if (sideLeader != null) SumoVehicle.getSpeed(sideLeader.id) else cruiseSpeedInMps

    val gain = vSideLeader - curLeaderSpeed
    val stuckBonus = if (stuck) 0.5 * laneChangeMinGainInMps else 0.0

    val score = sideLeaderWeight * gain + stuckBonus
    val feasible = stuck || (score > laneChangeMinGainInMps)

    return LaneEval(dir, feasible = feasible, score = score)
  }

  /**
   * Conservative side-corridor check: a lane change is only allowed if all lanes on the chosen side
   * of the ego are free within a local front/back window.
   *
   * This prevents simultaneous merges into the same middle lane, e.g.: ego on left lane, another
   * vehicle on right lane, both trying to enter the middle lane.
   */
  private fun areAllLanesOnSideFree(egoId: String, dir: Int): Boolean {
    val wantRight = dir < 0
    val wantLeft = dir > 0
    if (!wantLeft && !wantRight) return false

    val egoRoadId = SumoVehicle.getRoadID(egoId)
    val egoLaneIndex = SumoVehicle.getLaneIndex(egoId)
    val egoLanePos = SumoVehicle.getLanePosition(egoId)

    for (otherId in SumoVehicle.getIDList()) {
      if (otherId == egoId) continue
      if (SumoVehicle.getRoadID(otherId) != egoRoadId) continue

      val otherLaneIndex = SumoVehicle.getLaneIndex(otherId)

      val isOnChosenSide =
          if (wantRight) {
            otherLaneIndex < egoLaneIndex
          } else {
            otherLaneIndex > egoLaneIndex
          }

      if (!isOnChosenSide) continue

      val otherPos = SumoVehicle.getLanePosition(otherId)
      val delta = otherPos - egoLanePos

      val tooCloseBehind = delta >= -laneChangeSideBackGapInMeters
      val tooCloseAhead = delta <= laneChangeSideFrontGapInMeters

      if (tooCloseBehind && tooCloseAhead) return false
    }

    return true
  }

  private fun chooseDirection(left: LaneEval, right: LaneEval): Int? {
    val leftOk = left.feasible
    val rightOk = right.feasible

    if (!leftOk && !rightOk) return null
    if (leftOk && !rightOk) return left.dir
    if (!leftOk && rightOk) return right.dir

    // both feasible
    return if (left.score > right.score) left.dir
    else if (right.score > left.score) right.dir else if (preferLeftLane) left.dir else right.dir
  }

  /**
   * Adjacent lane free if no BLOCKING neighbors ahead/behind in that direction. getNeighbors(mode)
   * bits:
   * - 2^0: right neighbors (else left)
   * - 2^1: ahead (else behind)
   * - 2^2: blocking only (else all)
   */
  private fun isTargetDirectionFree(egoId: String, dir: Int): Boolean {
    val wantRight = dir < 0
    val wantLeft = dir > 0
    if (!wantLeft && !wantRight) return false // dir == 0

    // Mode bits (as Int):
    // bit0: right neighbors (else left)
    // bit1: ahead (else behind)
    // bit2: only blocking neighbors (else all)
    val bitRight = 1
    val bitAhead = 2
    val bitBlockingOnly = 4

    val rightBit = if (wantRight) bitRight else 0
    val modeAheadBlocking = bitBlockingOnly or rightBit or bitAhead
    val modeBehindBlocking = bitBlockingOnly or rightBit

    val ahead =
        runCatching { SumoVehicle.getNeighbors(egoId, modeAheadBlocking) }.getOrNull().orEmpty()
    if (ahead.isNotEmpty()) return false

    val behind =
        runCatching { SumoVehicle.getNeighbors(egoId, modeBehindBlocking) }.getOrNull().orEmpty()
    if (behind.isNotEmpty()) return false

    return true
  }

  /**
   * Picks nearest neighbor ahead on the side lane (not only blocking), as a speed-gain proxy. This
   * is intentionally written as a readable loop (more mutation points).
   */
  private fun getSideLeaderAhead(egoId: String, dir: Int): Neighbor? {
    val wantRight = dir < 0
    val wantLeft = dir > 0
    if (!wantLeft && !wantRight) return null

    val bitRight = 1
    val bitAhead = 2

    val rightBit = if (wantRight) bitRight else 0
    val modeAheadAll = rightBit or bitAhead

    val neighbors =
        runCatching { SumoVehicle.getNeighbors(egoId, modeAheadAll) }.getOrNull().orEmpty()

    var best: Neighbor? = null
    for (n in neighbors) {
      val id = n.first
      val dist = n.second

      if (id.isEmpty()) continue
      if (dist <= 0.0) continue
      if (dist > neighborLookAheadInMeters) continue

      if (best == null) {
        best = Neighbor(id, dist)
      } else {
        if (dist < best.distM) best = Neighbor(id, dist)
      }
    }
    return best
  }
}
