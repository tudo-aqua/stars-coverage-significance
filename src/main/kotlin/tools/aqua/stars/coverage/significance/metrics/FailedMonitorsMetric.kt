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

import java.util.UUID
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.g0Accidents
import tools.aqua.stars.coverage.significance.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.g2EmergencyBraking
import tools.aqua.stars.coverage.significance.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.g4TrafficFlow
import tools.aqua.stars.coverage.significance.i1Stopping
import tools.aqua.stars.coverage.significance.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * Metric that tracks which monitors have failed for each TSC instance at each tick.
 *
 * @property dependsOn Optional dependency on another metric.
 * @property tscId UUID of the TSC being evaluated.
 * @property writeToDb Decides, whether the monitor results should be written to the database, or
 *   not.
 */
class FailedMonitorsMetric(
    override val dependsOn: Any? = null,
    val tscId: UUID,
    val writeToDb: Boolean = true
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  private val failedMonitorsResult =
      mutableMapOf<
          TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
          MutableMap<Pair<UUID, UUID>, MetricFailedMonitorsEntry>>()

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val tscMap = failedMonitorsResult.getOrPut(tsc) { mutableMapOf() }
    checkNotNull(tick.mutantId) { "Tick mutantId is null." }
    val failedMonitorsEntry =
        tscMap.getOrPut(tick.mutantId to tick.scenarioConfigId) {
          MetricFailedMonitorsEntry(
              runId = tick.runId,
              tscId = tscId,
              mutantId = tick.mutantId,
              scenarioConfigId = tick.scenarioConfigId,
              monitorG0Failed = false,
              monitorG1Failed = false,
              monitorG2Failed = false,
              monitorG3Failed = false,
              monitorG4Failed = false,
              monitorI1Failed = false,
              monitorI2Failed = false)
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
  }

  override fun postEvaluate() {
    if (writeToDb) {
      db {
        failedMonitorsResult.keys.forEach { tsc ->
          val map = failedMonitorsResult[tsc]
          checkNotNull(map)
          val tscEntry = TSCsRepository.getByJson(tsc.getJsonString())
          checkNotNull(tscEntry) { "TSC not found in DB." }
          val failedMonitorsEntries = map.values.toList()
          MetricFailedMonitorsRepository.batchInsert(failedMonitorsEntries)
        }
      }
    }
  }

  override fun printPostEvaluationResult() {
    if (!writeToDb) {
      failedMonitorsResult.keys.forEach { tsc ->
        val map = failedMonitorsResult[tsc]
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
