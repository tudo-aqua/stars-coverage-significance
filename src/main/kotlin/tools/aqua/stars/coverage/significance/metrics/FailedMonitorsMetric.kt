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

package tools.aqua.stars.coverage.significance.metrics

import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCInstanceEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.tsc.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.tsc.g2EmergencyBraking
import tools.aqua.stars.coverage.significance.tsc.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.tsc.g4TrafficFlow
import tools.aqua.stars.coverage.significance.tsc.i1Stopping
import tools.aqua.stars.coverage.significance.tsc.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G0Accidents
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G1SafeDistance
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G2EmergencyBraking
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G4TrafficFlow
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I1Stopping
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I2FasterThanLeftTraffic
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * Metric that tracks which monitors have failed for each TSC instance at each tick.
 *
 * @property dependsOn Optional dependency on another metric.
 * @property writeToDb Decides, whether the monitor results should be written to the database, or
 *   not.
 */
class FailedMonitorsMetric(override val dependsOn: Any? = null, val writeToDb: Boolean = true) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  private val failedMonitorsPerTickResult =
      mutableMapOf<
          TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
          MutableMap<Triple<Int, Int, Long>, MetricFailedMonitorsEntry>>()

  private val tscInstanceEntries: List<TSCInstanceEntry>
  private val tscEntries: List<TSCEntry>

  init {
    tscInstanceEntries = TSCInstancesRepository.getAll()
    tscEntries = TSCsRepository.getAll()
  }

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val tscId = tscEntries.first { it.tscJson == tsc.getJsonString() }.id
    checkNotNull(tscId) { "TSC not found in database." }
    val tscMap = failedMonitorsPerTickResult.getOrPut(tsc) { mutableMapOf() }
    checkNotNull(tick.mutantId) { "Tick mutantId is null." }

    val failedMonitorInstances = tscInstance.rootNode.validateMonitors(tick.identifier)
    val setOfFailedMonitors = mutableSetOf<MonitorViolation>()
    failedMonitorInstances.forEach { violatedMonitor ->
      when (violatedMonitor.monitorLabel) {
        g0Accidents.name -> setOfFailedMonitors.add(G0Accidents)
        g1SafeDistanceToPrecedingVehicle.name -> setOfFailedMonitors.add(G1SafeDistance)
        g2EmergencyBraking.name -> setOfFailedMonitors.add(G2EmergencyBraking)
        g3MaximumSpeedLimit.name -> setOfFailedMonitors.add(G3MaximumSpeedLimit)
        g4TrafficFlow.name -> setOfFailedMonitors.add(G4TrafficFlow)
        i1Stopping.name -> setOfFailedMonitors.add(I1Stopping)
        i2DrivingFasterThenLeftTraffic.name -> setOfFailedMonitors.add(I2FasterThanLeftTraffic)
      }
    }

    val currentTSCInstanceId =
        tscInstanceEntries
            .first { it.tscId == tscId && it.instanceJson == tscInstance.getJsonString() }
            .id
    checkNotNull(currentTSCInstanceId) { "TSC instance not found in database." }

    // Determine lastTickTSCInstanceId and previousTSCInstanceId by scanning existing entries
    val currentTickTime = tick.tickTimeMillis
    val previousEntries =
        tscMap.filterKeys { (mutantId, scenarioConfigId, tickTime) ->
          mutantId == tick.mutantId &&
              scenarioConfigId == tick.scenarioConfigId &&
              tickTime < currentTickTime
        }

    val closestEntry =
        previousEntries
            .minByOrNull { (key, _) -> kotlin.math.abs(key.third - currentTickTime) }
            ?.value
    val lastTickId = closestEntry?.currentTSCInstanceId

    val previousDifferentEntry =
        previousEntries.entries
            .sortedByDescending { it.key.third }
            .map { it.value }
            .firstOrNull { it.currentTSCInstanceId != currentTSCInstanceId }

    val previousId = previousDifferentEntry?.currentTSCInstanceId
    val previousTick = previousDifferentEntry?.tick

    val surroundingDistances = tick.egoSurroundingVehicleDistances
    val egoSpeed = tick.ego.speedMetersPerSecond
    val egoAccel = tick.ego.accelerationMetersPerSecondSquared
    val egoFrontBumperPos = tick.ego.frontBumperPositionOnLaneMeters
    val egoBackBumperPos = tick.ego.backBumperPositionOnLaneMeters

    val failedMonitorsEntry =
        tscMap.getOrPut(Triple(tick.mutantId, tick.scenarioConfigId, currentTickTime)) {
          MetricFailedMonitorsEntry(
              runId = tick.runId,
              tscId = tscId,
              mutantId = tick.mutantId,
              scenarioConfigId = tick.scenarioConfigId,
              currentTSCInstanceId = currentTSCInstanceId,
              lastTickTSCInstanceId = lastTickId,
              previousTSCInstanceId = previousId,
              previousTSCInstanceTick = previousTick,
              tick = currentTickTime,
              egoManeuverSpeed = tick.egoManeuver?.newSpeedMps?.toFloat(),
              egoManeuverLangeChange = tick.egoManeuver?.laneChangeDirection,
              monitorG0Failed = false,
              monitorG1Failed = false,
              monitorG2Failed = false,
              monitorG3Failed = false,
              monitorG4Failed = false,
              monitorI1Failed = false,
              monitorI2Failed = false,
              surroundingDistFront = surroundingDistances?.frontMeters?.toFloat(),
              surroundingDistRear = surroundingDistances?.rearMeters?.toFloat(),
              surroundingDistFrontLeft = surroundingDistances?.frontLeftMeters?.toFloat(),
              surroundingDistFrontRight = surroundingDistances?.frontRightMeters?.toFloat(),
              surroundingDistRearLeft = surroundingDistances?.rearLeftMeters?.toFloat(),
              surroundingDistRearRight = surroundingDistances?.rearRightMeters?.toFloat(),
              surroundingDistLeft = surroundingDistances?.leftMeters?.toFloat(),
              surroundingDistRight = surroundingDistances?.rightMeters?.toFloat(),
              egoSpeedMps = egoSpeed,
              egoAccelMps2 = egoAccel,
              egoFrontBumperPosMeters = egoFrontBumperPos,
              egoBackBumperPosMeters = egoBackBumperPos,
              surroundingFrontSpeedMps = surroundingDistances?.frontSpeedMps?.toFloat(),
              surroundingFrontFrontBumperPosMeters =
                  surroundingDistances?.frontFrontBumperPositionMeters?.toFloat(),
              surroundingFrontBackBumperPosMeters =
                  surroundingDistances?.frontBackBumperPositionMeters?.toFloat(),
              surroundingFrontAccelMps2 = surroundingDistances?.frontAccelMps2?.toFloat(),
              surroundingFrontSpeedDiffMps =
                  surroundingDistances?.frontSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingFrontAccelDiffMps2 =
                  surroundingDistances?.frontAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingFrontTtcSeconds =
                  ttcAhead(
                      surroundingDistances?.frontMeters,
                      surroundingDistances?.frontSpeedMps,
                      egoSpeed),
              surroundingFrontTgSeconds = tgEgo(surroundingDistances?.frontMeters, egoSpeed),
              surroundingRearSpeedMps = surroundingDistances?.rearSpeedMps?.toFloat(),
              surroundingRearFrontBumperPosMeters =
                  surroundingDistances?.rearFrontBumperPositionMeters?.toFloat(),
              surroundingRearBackBumperPosMeters =
                  surroundingDistances?.rearBackBumperPositionMeters?.toFloat(),
              surroundingRearAccelMps2 = surroundingDistances?.rearAccelMps2?.toFloat(),
              surroundingRearSpeedDiffMps =
                  surroundingDistances?.rearSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingRearAccelDiffMps2 =
                  surroundingDistances?.rearAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingRearTtcSeconds =
                  ttcBehind(
                      surroundingDistances?.rearMeters,
                      surroundingDistances?.rearSpeedMps,
                      egoSpeed),
              surroundingRearTgSeconds =
                  tgNeighbor(surroundingDistances?.rearMeters, surroundingDistances?.rearSpeedMps),
              surroundingFrontLeftSpeedMps = surroundingDistances?.frontLeftSpeedMps?.toFloat(),
              surroundingFrontLeftFrontBumperPosMeters =
                  surroundingDistances?.frontLeftFrontBumperPositionMeters?.toFloat(),
              surroundingFrontLeftBackBumperPosMeters =
                  surroundingDistances?.frontLeftBackBumperPositionMeters?.toFloat(),
              surroundingFrontLeftAccelMps2 = surroundingDistances?.frontLeftAccelMps2?.toFloat(),
              surroundingFrontLeftSpeedDiffMps =
                  surroundingDistances?.frontLeftSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingFrontLeftAccelDiffMps2 =
                  surroundingDistances?.frontLeftAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingFrontLeftTtcSeconds =
                  ttcAhead(
                      surroundingDistances?.frontLeftMeters,
                      surroundingDistances?.frontLeftSpeedMps,
                      egoSpeed),
              surroundingFrontLeftTgSeconds =
                  tgEgo(surroundingDistances?.frontLeftMeters, egoSpeed),
              surroundingFrontRightSpeedMps = surroundingDistances?.frontRightSpeedMps?.toFloat(),
              surroundingFrontRightFrontBumperPosMeters =
                  surroundingDistances?.frontRightFrontBumperPositionMeters?.toFloat(),
              surroundingFrontRightBackBumperPosMeters =
                  surroundingDistances?.frontRightBackBumperPositionMeters?.toFloat(),
              surroundingFrontRightAccelMps2 = surroundingDistances?.frontRightAccelMps2?.toFloat(),
              surroundingFrontRightSpeedDiffMps =
                  surroundingDistances?.frontRightSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingFrontRightAccelDiffMps2 =
                  surroundingDistances?.frontRightAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingFrontRightTtcSeconds =
                  ttcAhead(
                      surroundingDistances?.frontRightMeters,
                      surroundingDistances?.frontRightSpeedMps,
                      egoSpeed),
              surroundingFrontRightTgSeconds =
                  tgEgo(surroundingDistances?.frontRightMeters, egoSpeed),
              surroundingRearLeftSpeedMps = surroundingDistances?.rearLeftSpeedMps?.toFloat(),
              surroundingRearLeftFrontBumperPosMeters =
                  surroundingDistances?.rearLeftFrontBumperPositionMeters?.toFloat(),
              surroundingRearLeftBackBumperPosMeters =
                  surroundingDistances?.rearLeftBackBumperPositionMeters?.toFloat(),
              surroundingRearLeftAccelMps2 = surroundingDistances?.rearLeftAccelMps2?.toFloat(),
              surroundingRearLeftSpeedDiffMps =
                  surroundingDistances?.rearLeftSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingRearLeftAccelDiffMps2 =
                  surroundingDistances?.rearLeftAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingRearLeftTtcSeconds =
                  ttcBehind(
                      surroundingDistances?.rearLeftMeters,
                      surroundingDistances?.rearLeftSpeedMps,
                      egoSpeed),
              surroundingRearLeftTgSeconds =
                  tgNeighbor(
                      surroundingDistances?.rearLeftMeters, surroundingDistances?.rearLeftSpeedMps),
              surroundingRearRightSpeedMps = surroundingDistances?.rearRightSpeedMps?.toFloat(),
              surroundingRearRightFrontBumperPosMeters =
                  surroundingDistances?.rearRightFrontBumperPositionMeters?.toFloat(),
              surroundingRearRightBackBumperPosMeters =
                  surroundingDistances?.rearRightBackBumperPositionMeters?.toFloat(),
              surroundingRearRightAccelMps2 = surroundingDistances?.rearRightAccelMps2?.toFloat(),
              surroundingRearRightSpeedDiffMps =
                  surroundingDistances?.rearRightSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingRearRightAccelDiffMps2 =
                  surroundingDistances?.rearRightAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingRearRightTtcSeconds =
                  ttcBehind(
                      surroundingDistances?.rearRightMeters,
                      surroundingDistances?.rearRightSpeedMps,
                      egoSpeed),
              surroundingRearRightTgSeconds =
                  tgNeighbor(
                      surroundingDistances?.rearRightMeters,
                      surroundingDistances?.rearRightSpeedMps),
              surroundingLeftSpeedMps = surroundingDistances?.leftSpeedMps?.toFloat(),
              surroundingLeftFrontBumperPosMeters =
                  surroundingDistances?.leftFrontBumperPositionMeters?.toFloat(),
              surroundingLeftBackBumperPosMeters =
                  surroundingDistances?.leftBackBumperPositionMeters?.toFloat(),
              surroundingLeftAccelMps2 = surroundingDistances?.leftAccelMps2?.toFloat(),
              surroundingLeftSpeedDiffMps =
                  surroundingDistances?.leftSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingLeftAccelDiffMps2 =
                  surroundingDistances?.leftAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingLeftTtcSeconds =
                  ttcBeside(
                      surroundingDistances?.leftMeters,
                      surroundingDistances?.leftSpeedMps,
                      egoSpeed),
              surroundingLeftTgSeconds = tgEgo(surroundingDistances?.leftMeters, egoSpeed),
              surroundingRightSpeedMps = surroundingDistances?.rightSpeedMps?.toFloat(),
              surroundingRightFrontBumperPosMeters =
                  surroundingDistances?.rightFrontBumperPositionMeters?.toFloat(),
              surroundingRightBackBumperPosMeters =
                  surroundingDistances?.rightBackBumperPositionMeters?.toFloat(),
              surroundingRightAccelMps2 = surroundingDistances?.rightAccelMps2?.toFloat(),
              surroundingRightSpeedDiffMps =
                  surroundingDistances?.rightSpeedMps?.let { (it - egoSpeed).toFloat() },
              surroundingRightAccelDiffMps2 =
                  surroundingDistances?.rightAccelMps2?.let { (it - egoAccel).toFloat() },
              surroundingRightTtcSeconds =
                  ttcBeside(
                      surroundingDistances?.rightMeters,
                      surroundingDistances?.rightSpeedMps,
                      egoSpeed),
              surroundingRightTgSeconds = tgEgo(surroundingDistances?.rightMeters, egoSpeed),
          )
        }
    val violatedMonitors = tscInstance.rootNode.validateMonitors(tick.identifier)
    violatedMonitors.forEach { violatedMonitor ->
      when (violatedMonitor.monitorLabel) {
        g0Accidents.name -> failedMonitorsEntry.monitorG0Failed = true
        g1SafeDistanceToPrecedingVehicle.name -> failedMonitorsEntry.monitorG1Failed = true
        g2EmergencyBraking.name -> failedMonitorsEntry.monitorG2Failed = true
        g3MaximumSpeedLimit.name -> failedMonitorsEntry.monitorG3Failed = true
        g4TrafficFlow.name -> failedMonitorsEntry.monitorG4Failed = true
        i1Stopping.name -> failedMonitorsEntry.monitorI1Failed = true
        i2DrivingFasterThenLeftTraffic.name -> failedMonitorsEntry.monitorI2Failed = true
      }
    }

    // Back-populate the previous tick's "next tick" monitor fields with this tick's results.
    if (closestEntry != null) {
      closestEntry.nextTickMonitorG0Failed = G0Accidents in setOfFailedMonitors
      closestEntry.nextTickMonitorG1Failed = G1SafeDistance in setOfFailedMonitors
      closestEntry.nextTickMonitorG2Failed = G2EmergencyBraking in setOfFailedMonitors
      closestEntry.nextTickMonitorG3Failed = G3MaximumSpeedLimit in setOfFailedMonitors
      closestEntry.nextTickMonitorG4Failed = G4TrafficFlow in setOfFailedMonitors
      closestEntry.nextTickMonitorI1Failed = I1Stopping in setOfFailedMonitors
      closestEntry.nextTickMonitorI2Failed = I2FasterThanLeftTraffic in setOfFailedMonitors
    }
  }

  override fun postEvaluate() {
    if (writeToDb) {
      db {
        failedMonitorsPerTickResult.keys.forEach { tsc ->
          val map = failedMonitorsPerTickResult[tsc]
          checkNotNull(map)
          val tscEntry = TSCsRepository.getByJson(tsc.getJsonString())
          checkNotNull(tscEntry) { "TSC not found in DB." }
          val failedMonitorsEntries = map.values.toList()
          MetricFailedMonitorsRepository.batchInsert(failedMonitorsEntries)
        }
      }
    }
  }

  /** TTC when ego is behind [neighborSpeed]: `dist / (egoSpeed − neighborSpeed)` when closing. */
  private fun ttcAhead(dist: Double?, neighborSpeed: Double?, egoSpeed: Float): Float? {
    dist ?: return null
    neighborSpeed ?: return null
    val closing = egoSpeed - neighborSpeed
    return if (dist > 0 && closing > 0) (dist / closing).toFloat() else null
  }

  /** TTC when neighbor is behind ego: `dist / (neighborSpeed − egoSpeed)` when closing. */
  private fun ttcBehind(dist: Double?, neighborSpeed: Double?, egoSpeed: Float): Float? {
    dist ?: return null
    neighborSpeed ?: return null
    val closing = neighborSpeed - egoSpeed
    return if (dist > 0 && closing > 0) (dist / closing).toFloat() else null
  }

  /** TTC for a beside-zone neighbour: `dist / |egoSpeed − neighborSpeed|` when speeds differ. */
  private fun ttcBeside(dist: Double?, neighborSpeed: Double?, egoSpeed: Float): Float? {
    dist ?: return null
    neighborSpeed ?: return null
    val closing = kotlin.math.abs(egoSpeed - neighborSpeed)
    return if (closing > 0) (dist / closing).toFloat() else null
  }

  /** Time gap from ego's perspective: `dist / egoSpeed` (front and beside directions). */
  private fun tgEgo(dist: Double?, egoSpeed: Float): Float? {
    dist ?: return null
    return if (dist > 0 && egoSpeed > 0) (dist / egoSpeed).toFloat() else null
  }

  /** Time gap from the follower's perspective: `dist / neighborSpeed` (rear directions). */
  private fun tgNeighbor(dist: Double?, neighborSpeed: Double?): Float? {
    dist ?: return null
    neighborSpeed ?: return null
    return if (dist > 0 && neighborSpeed > 0) (dist / neighborSpeed).toFloat() else null
  }

  override fun printPostEvaluationResult() {
    if (!writeToDb) {
      failedMonitorsPerTickResult.keys.forEach { tsc ->
        val map = failedMonitorsPerTickResult[tsc]
        checkNotNull(map)
        map.forEach { (mutantId, scenarioConfigId), failedMetric ->
          println(
              "${g0Accidents.name}: ${if (failedMetric.monitorG0Failed) "failed" else
       "passed"}")
          println(
              "${g1SafeDistanceToPrecedingVehicle.name}: ${if (failedMetric.monitorG1Failed)
       "failed" else "passed"}")
          println(
              "${g2EmergencyBraking.name}: ${if (failedMetric.monitorG2Failed) "failed" else
       "passed"}")
          println(
              "${g3MaximumSpeedLimit.name}: ${if (failedMetric.monitorG3Failed) "failed" else
       "passed"}")
          println(
              "${g4TrafficFlow.name}: ${if (failedMetric.monitorG4Failed) "failed" else
       "passed"}")
          println(
              "${i1Stopping.name}: ${if (failedMetric.monitorI1Failed) "failed" else
       "passed"}")
          println(
              "${i2DrivingFasterThenLeftTraffic.name}: ${if (failedMetric.monitorI2Failed)
       "failed" else "passed"}")
          println("----------------------------------------")
        }
      }
    }
  }
}
