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

package tools.aqua.stars.sumo

import org.eclipse.sumo.libsumo.Simulation
import org.eclipse.sumo.libsumo.StringDoublePair
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle

class SimpleAutopilot {

  // -------------------- ACC parameters --------------------
  var cruiseSpeedInMps = 27.77
  var timeHeadwayToLeaderInSeconds = 1.3
  var minGapToLeadingInMeters = 5.0

  var maxAccelMps2 = 2.0
  var maxDecelMps2 = 4.5
  var stepLengthSeconds = 0.1

  // Controller knobs (extra mutation points)
  var gapGain = 0.5
  var relSpeedGain = 0.2
  var hardBrakeGapFactor = 0.6
  var minTargetSpeedMps = 0.0

  // -------------------- Lane change parameters --------------------
  var laneChangeCooldownInSeconds = 2.0
  var lcMinGainMps = 1.0
  var neighborLookAheadM = 150.0
  var leaderLookAheadM = 200.0

  // LC variation points
  var preferLeftLane = true
  var stuckGapFactor = 1.0
  var stuckSpeedDeltaMps = 0.5
  var sideLeaderWeight = 1.0
  var maxLaneChangeDurationS = 1.0

  private var lastLaneChangeSimTimeInSeconds = -1e9

  // -------------------- Public tick --------------------
  fun controlTick(egoId: String) {
    val vEgo = SumoVehicle.getSpeed(egoId)
    val leader = leaderInfoOrNull(egoId)

    val desiredGap = desiredGapMeters(vEgo)

    val vTarget = desiredSpeedAcc(vEgo, desiredGap, leader)
    val vCmd = clampSpeedWithAccelLimits(vEgo, vTarget, stepLengthSeconds)

    SumoVehicle.setSpeed(egoId, vCmd)

    maybeLaneChange(egoId, vEgo, desiredGap, leader)
  }

  // -------------------- ACC --------------------
  private fun leaderInfoOrNull(egoId: String): StringDoublePair? =
      runCatching { SumoVehicle.getLeader(egoId, leaderLookAheadM) }
          .getOrNull()
          ?.takeIf { it.first.isNotEmpty() }

  private fun desiredGapMeters(vEgo: Double): Double =
      minGapToLeadingInMeters + timeHeadwayToLeaderInSeconds * vEgo

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
    val followProposal = vLeader + gapGain * gapError + relSpeedGain * relSpeed
    if (followProposal < vTarget) vTarget = followProposal

    // Extra safety-ish branch: if too close, bias towards braking
    if (gap < hardBrakeGapFactor * desiredGap) {
      val penalty = absVal(gapError) * 0.3
      val hardProposal = vLeader - penalty
      if (hardProposal < vTarget) vTarget = hardProposal
    }

    // clamp lower bound
    if (vTarget < minTargetSpeedMps) vTarget = minTargetSpeedMps
    // clamp upper bound (explicit, no min())
    if (vTarget > cruiseSpeedInMps) vTarget = cruiseSpeedInMps

    return vTarget
  }

  private fun clampSpeedWithAccelLimits(vNow: Double, vTarget: Double, dt: Double): Double {
    val dvWanted = vTarget - vNow
    val dvMaxUp = maxAccelMps2 * dt
    val dvMaxDown = -maxDecelMps2 * dt

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
  ) {
    val now = Simulation.getTime()
    if (now - lastLaneChangeSimTimeInSeconds < laneChangeCooldownInSeconds) return

    val baseLaneIndex = SumoVehicle.getLaneIndex(egoId)

    val curLeaderSpeed =
        if (leader != null) SumoVehicle.getSpeed(leader.first) else cruiseSpeedInMps
    val curGap = if (leader != null) leader.second else Double.POSITIVE_INFINITY

    val stuck = isStuck(vEgo, curLeaderSpeed, curGap, desiredGap)

    val left = evaluateLaneChange(egoId, dir = +1, stuck = stuck, curLeaderSpeed = curLeaderSpeed)
    val right = evaluateLaneChange(egoId, dir = -1, stuck = stuck, curLeaderSpeed = curLeaderSpeed)

    val chosenDir = chooseDirection(left, right) ?: return

    val targetLaneIndex = baseLaneIndex + chosenDir
    if (targetLaneIndex < 0) return

    SumoVehicle.changeLane(egoId, targetLaneIndex, maxLaneChangeDurationS)
    lastLaneChangeSimTimeInSeconds = now
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
    if (dir != 1 && dir != -1)
        return LaneEval(dir, feasible = false, score = Double.NEGATIVE_INFINITY)

    val safe = isTargetDirectionFree(egoId, dir)
    if (!safe) return LaneEval(dir, feasible = false, score = Double.NEGATIVE_INFINITY)

    val sideLeader = getSideLeaderAhead(egoId, dir)
    val vSideLeader =
        if (sideLeader != null) SumoVehicle.getSpeed(sideLeader.id) else cruiseSpeedInMps

    val gain = vSideLeader - curLeaderSpeed
    val stuckBonus = if (stuck) 0.5 * lcMinGainMps else 0.0

    val score = sideLeaderWeight * gain + stuckBonus

    val feasible = stuck || (score > lcMinGainMps)
    return LaneEval(dir, feasible = feasible, score = score)
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
    val wantRight = dir == -1
    val wantLeft = dir == 1
    if (!wantLeft && !wantRight) return false

    val blockingOnly = 0b100
    val rightBit = if (wantRight) 0b001 else 0b000

    val modeAheadBlocking = blockingOnly or rightBit or 0b010
    val modeBehindBlocking = blockingOnly or rightBit

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
    val wantRight = dir == -1
    val wantLeft = dir == 1
    if (!wantLeft && !wantRight) return null

    val rightBit = if (wantRight) 0b001 else 0b000
    val modeAheadAll = rightBit or 0b010

    val neighbors =
        runCatching { SumoVehicle.getNeighbors(egoId, modeAheadAll) }.getOrNull().orEmpty()

    var best: Neighbor? = null
    for (n in neighbors) {
      val id = n.first
      val dist = n.second

      if (id.isEmpty()) continue
      if (dist <= 0.0) continue
      if (dist > neighborLookAheadM) continue

      if (best == null) {
        best = Neighbor(id, dist)
      } else {
        if (dist < best.distM) best = Neighbor(id, dist)
      }
    }
    return best
  }
}
