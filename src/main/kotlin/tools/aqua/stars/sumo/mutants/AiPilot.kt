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

import java.io.File
import kotlin.math.tanh
import org.eclipse.sumo.libsumo.StringDoublePair
import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle
import tools.aqua.stars.sumo.Autopilot.Neighbor

class AiPilot : Mutant() {

  /** The distance ahead to look for a neighbor. */
  var neighborLookAheadInMeters = 150.0
  var leaderLookAheadInMeters = 150.0

  val weights: Array<Array<Array<Double>>> = loadWeights()
  val bias: Array<Array<Double>> = loadBias()

  override fun controlTick(egoId: String) {
    var speed = SumoVehicle.getSpeed(egoId)
    var laneIndex = SumoVehicle.getLaneIndex(egoId).toDouble()
    var leftLane = neighborLookAheadInMeters
    var frontLane = neighborLookAheadInMeters
    var rightLane = neighborLookAheadInMeters
    var leader = getSideLeaderAhead(egoId, 1)
    if (leader != null) {
      leftLane = leader.distM
    }
    val frontLeader = leaderInfoOrNull(egoId)
    if (frontLeader != null) {
      frontLane = frontLeader.second
    }
    leader = getSideLeaderAhead(egoId, -1)
    if (leader != null) {
      rightLane = leader.distM
    }

    speed = speed / 20 - 1
    laneIndex = laneIndex - 1
    leftLane = leftLane / 75 - 1
    frontLane = frontLane / 75 - 1
    rightLane = rightLane / 75 - 1

    val actions = forward(speed, laneIndex, leftLane, frontLane, rightLane)

    if (actions[0] >= 1) SumoVehicle.changeLane(egoId, SumoVehicle.getLaneIndex(egoId) + 1, 1.0)
    else if (actions[0] <= -1)
        SumoVehicle.changeLane(egoId, SumoVehicle.getLaneIndex(egoId) - 1, 1.0)

    SumoVehicle.setAcceleration(egoId, actions[1], 0.1)
  }

  private fun leaderInfoOrNull(egoId: String): StringDoublePair? =
      runCatching { SumoVehicle.getLeader(egoId, leaderLookAheadInMeters) }
          .getOrNull()
          ?.takeIf { it.first.isNotEmpty() }

  private data class Neighbor(val id: String, val distM: Double)

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

  private fun loadWeights(): Array<Array<Array<Double>>> {
    val lines = File("NeuralNetwork.txt").readLines()
    val layerCount = lines[0].toInt()
    val loadedWeights = Array<Array<Array<Double>>>(layerCount) { emptyArray() }
    var index = -1
    for (i in 1..layerCount * 3) {
      if (i % 3 == 1) {
        index++
        val currentLine = lines[i].split(";")
        val inSize = currentLine[0].toInt()
        val outSize = currentLine[1].toInt()
        loadedWeights[index] = Array<Array<Double>>(outSize) { Array<Double>(inSize) { 0.0 } }
      } else if (i % 3 == 2) {
        val currentLine = lines[i].split(";")
        var x = 0
        var y = 0
        for (weight in currentLine) {
          loadedWeights[index][x][y++] = weight.toDouble()
          if (y >= loadedWeights[index][x].size) {
            x++
            y = 0
          }
        }
      }
    }
    return loadedWeights
  }

  private fun loadBias(): Array<Array<Double>> {
    val lines = File("NeuralNetwork.txt").readLines()
    val layerCount = lines[0].toInt()
    val loadedBias = Array<Array<Double>>(layerCount) { emptyArray() }
    var index = -1
    for (i in 1..layerCount * 3) {
      if (i % 3 == 1) {
        index++
        val currentLine = lines[i].split(";")
        val outSize = currentLine[1].toInt()
        loadedBias[index] = Array<Double>(outSize) { 0.0 }
      } else if (i % 3 == 0) {
        val currentLine = lines[i].split(";")
        var x = 0
        for (b in currentLine) {
          loadedBias[index][x++] = b.toDouble()
        }
      }
    }
    return loadedBias
  }

  private fun forward(
      speed: Double,
      laneIndex: Double,
      leftLane: Double,
      frontLane: Double,
      rightLane: Double
  ): DoubleArray {
    var currentValues = DoubleArray(5)

    currentValues[0] = speed
    currentValues[1] = laneIndex
    currentValues[2] = leftLane
    currentValues[3] = frontLane
    currentValues[4] = rightLane

    var biggestValue = 0.0
    var smallestValue = 0.0

    for (index in 0..<weights.size) {
      var useRelu = true
      if (index == weights.size - 1) {
        useRelu = false
      }

      var previousValues = currentValues
      currentValues = DoubleArray(bias[index].size) { 0.0 }
      for (i in 0..<currentValues.size) {
        currentValues[i] = bias[index][i]
        for (j in 0..<weights[index][i].size) {
          currentValues[i] += previousValues[j] * weights[index][i][j]
        }

        if (currentValues[i] > biggestValue) biggestValue = currentValues[i]

        if (currentValues[i] < smallestValue) smallestValue = currentValues[i]

        if (useRelu) {
          currentValues[i] = currentValues[i].coerceAtLeast(0.0)
        } else {
          currentValues[i] = tanh(currentValues[i])
        }
      }
    }

    return currentValues
  }
}
