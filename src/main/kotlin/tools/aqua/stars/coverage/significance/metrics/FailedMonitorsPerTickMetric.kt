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
import tools.aqua.stars.coverage.significance.g0Accidents
import tools.aqua.stars.coverage.significance.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.g2EmergencyBraking
import tools.aqua.stars.coverage.significance.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.g4TrafficFlow
import tools.aqua.stars.coverage.significance.i1Stopping
import tools.aqua.stars.coverage.significance.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toReadableString
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toSetOfMonitorViolations
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G0Accidents
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G1SafeDistance
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G2EmergencyBraking
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G4TrafficFlow
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I1Stopping
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I2FasterThanLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolationBitmask
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * Metric that tracks which monitors have failed for each TSC instance at each tick.
 *
 * @property dependsOn This metric does not depend on any other metric.
 * @property writeToDb Whether to write the results to the database.
 */
class FailedMonitorsPerTickMetric(
    override val dependsOn: Any? = null,
    val writeToDb: Boolean = true
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  private val monitorFailuresPerTick = mutableListOf<FailedMonitorsPerTick>()

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
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

    val failedMonitorBitmask = setOfFailedMonitors.toBitmask()

    monitorFailuresPerTick.add(
        FailedMonitorsPerTick(
            tsc = tsc,
            tick = tick,
            failedMonitors = failedMonitorBitmask,
            tscInstance = tscInstance))
  }

  override fun postEvaluate() {}

  override fun printPostEvaluationResult() {
    val monitorFailuresPerTSC = monitorFailuresPerTick.groupBy { it.tsc }
    if (!writeToDb) {
      monitorFailuresPerTSC.forEach { (tsc, failedMonitors) ->
        println("TSC: $tsc")
        val monitorFailuresForTSCSorted =
            failedMonitors.sortedWith(
                compareBy<FailedMonitorsPerTick> { it.tick.sourceIdentifier }
                    .thenBy { it.tick.mutantId.toString() }
                    .thenBy { it.tick.tickTimeMillis })

        monitorFailuresForTSCSorted.forEach {
          println(
              "Tick: ${it.tick.tickTimeMillis}, Failed Monitors: ${it.failedMonitors.toReadableString()}")
          println("Instance: ${it.tscInstance} ")
          println("----------------------------------------")
        }

        printJoinedTimelineVisualization(failedMonitors)

        println("")
        println("----------------------------------------")
        println("-------------Statistics-----------------")
        println("----------------------------------------")
        println("")

        val failedMonitorsInTotal =
            failedMonitors.flatMap { it.failedMonitors.toSetOfMonitorViolations() }.toSet()
        println("Total Failed Monitors (${failedMonitorsInTotal.size}): $failedMonitorsInTotal")

        val countOfTSCInstances = failedMonitors.map { it.tscInstance }.toSet()
        println("Total TSC Instances: ${countOfTSCInstances.size}")
        countOfTSCInstances.forEach { println(it) }
      }
    }
  }

  /**
   * Creates a timeline where every tick is joined with a stable local ID for the TSC instance that
   * was active at that tick.
   */
  fun joinedTimeline(): List<TSCInstanceTimelineTick> {
    val instanceIds =
        monitorFailuresPerTick
            .sortedWith(
                compareBy<FailedMonitorsPerTick> { it.tick.sourceIdentifier }
                    .thenBy { it.tick.mutantId.toString() }
                    .thenBy { it.tick.tickTimeMillis })
            .distinctBy { it.tscInstance.getJsonString() }
            .mapIndexed { index, failure ->
              failure.tscInstance.getJsonString() to "Instance-${index + 1}"
            }
            .toMap()

    return monitorFailuresPerTick
        .sortedWith(
            compareBy<FailedMonitorsPerTick> { it.tick.sourceIdentifier }
                .thenBy { it.tick.mutantId.toString() }
                .thenBy { it.tick.tickTimeMillis })
        .map { failure ->
          TSCInstanceTimelineTick(
              tsc = failure.tsc,
              tick = failure.tick,
              failedMonitors = failure.failedMonitors,
              tscInstance = failure.tscInstance,
              tscInstanceId = instanceIds.getValue(failure.tscInstance.getJsonString()))
        }
  }

  /** Creates contiguous ranges for each TSC instance in the joined timeline. */
  fun tscInstanceRanges(): List<TSCInstanceTimelineRange> {
    val ranges = mutableListOf<TSCInstanceTimelineRange>()

    joinedTimeline()
        .groupBy { TimelineSource(it.tick.sourceIdentifier, it.tick.mutantId.toString()) }
        .forEach { (_, ticksForSource) ->
          ticksForSource
              .groupBy { it.tsc }
              .forEach { (_, ticksForTSC) ->
                var currentRangeTicks = mutableListOf<TSCInstanceTimelineTick>()

                ticksForTSC
                    .sortedBy { it.tick.tickTimeMillis }
                    .forEach { timelineTick ->
                      val continuesCurrentRange =
                          currentRangeTicks.lastOrNull()?.tscInstanceId ==
                              timelineTick.tscInstanceId

                      if (currentRangeTicks.isNotEmpty() && !continuesCurrentRange) {
                        ranges += currentRangeTicks.toTimelineRange()
                        currentRangeTicks = mutableListOf()
                      }

                      currentRangeTicks += timelineTick
                    }

                if (currentRangeTicks.isNotEmpty()) {
                  ranges += currentRangeTicks.toTimelineRange()
                }
              }
        }

    return ranges
  }

  private fun printJoinedTimelineVisualization(failedMonitors: List<FailedMonitorsPerTick>) {
    val relevantTSCs = failedMonitors.map { it.tsc }.toSet()
    val relevantTicks = joinedTimeline().filter { it.tsc in relevantTSCs }
    val relevantRanges =
        tscInstanceRanges().filter { range ->
          range.tsc in relevantTSCs && relevantTicks.any { it in range.ticks }
        }

    println("")
    println("----------------------------------------")
    println("------Joined TSC Instance Timeline------")
    println("----------------------------------------")

    relevantTicks
        .groupBy { TimelineSource(it.tick.sourceIdentifier, it.tick.mutantId.toString()) }
        .forEach { (source, ticksForSource) ->
          println("Source: ${source.sourceIdentifier}, Mutant: ${source.mutantId}")
          println("TSC instance ranges:")
          relevantRanges
              .filter { range ->
                range.sourceIdentifier == source.sourceIdentifier &&
                    range.mutantId == source.mutantId
              }
              .forEach { range ->
                println(
                    "${range.tscInstanceId}: ${range.fromTickMillis}..${range.toTickMillis} ms " +
                        "(${range.ticks.size} ticks)")
              }

          println("")
          println("Failed monitors by tick:")
          println("tick(ms) | tscInstance | failed monitors")
          ticksForSource
              .sortedBy { it.tick.tickTimeMillis }
              .forEach { tick ->
                println(
                    "${tick.tick.tickTimeMillis.toString().padStart(8)} | " +
                        "${tick.tscInstanceId.padEnd(11)} | " +
                        tick.failedMonitors.toTimelineLabel())
              }
          println("----------------------------------------")
        }
  }

  private fun List<TSCInstanceTimelineTick>.toTimelineRange(): TSCInstanceTimelineRange =
      TSCInstanceTimelineRange(
          tsc = first().tsc,
          tscInstance = first().tscInstance,
          tscInstanceId = first().tscInstanceId,
          sourceIdentifier = first().tick.sourceIdentifier,
          mutantId = first().tick.mutantId.toString(),
          fromTickMillis = first().tick.tickTimeMillis,
          toTickMillis = last().tick.tickTimeMillis,
          ticks = this)

  private fun MonitorViolationBitmask.toTimelineLabel(): String = toReadableString().ifBlank { "-" }
}

data class FailedMonitorsPerTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>
)

data class TSCInstanceTimelineTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>,
    val tscInstanceId: String
)

data class TSCInstanceTimelineRange(
    val tsc: TSC<*, *, *, *>,
    val tscInstance: TSCInstance<*, *, *, *>,
    val tscInstanceId: String,
    val sourceIdentifier: String,
    val mutantId: String,
    val fromTickMillis: Long,
    val toTickMillis: Long,
    val ticks: List<TSCInstanceTimelineTick>
)

private data class TimelineSource(val sourceIdentifier: String, val mutantId: String)
