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

import java.util.logging.Logger
import kotlin.collections.component1
import kotlin.collections.component2
import tools.aqua.stars.core.metrics.providers.Loggable
import tools.aqua.stars.core.metrics.providers.Plottable
import tools.aqua.stars.core.metrics.providers.Stateful
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.core.utils.ApplicationConstantsHolder.CONSOLE_INDENT
import tools.aqua.stars.core.utils.ApplicationConstantsHolder.CONSOLE_SEPARATOR
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.core.utils.plotDataAsBarChart
import tools.aqua.stars.data.sumo.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle

/**
 * Metric evaluating the first change in a TSC instance over time.
 *
 * @property loggerIdentifier identifier (name) for the logger.
 * @property logger [Logger] instance.
 */
class FirstTSCInstanceChangeMetric(
    override val loggerIdentifier: String = "first-tsc-instance-change",
    override val logger: Logger = Loggable.getLogger(loggerIdentifier),
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    Stateful,
    Loggable,
    Plottable {

  /**
   * Data class representing the first change in a TSC instance.
   *
   * @property changedFrom The TSC instance before the change.
   * @property changedTo The TSC instance after the change.
   * @property firstChangeAfterXUnits The time in units after which the first change occurred
   */
  data class FirstChangeData(
      val changedFrom:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      val changedTo:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>? =
          null,
      val firstChangeAfterXUnits: TickUnitMilliseconds? = null,
  )

  /**
   * Map storing the first change tick for each source identifier.
   * - Map<sourceIdentifier,FirstChangeData>.
   */
  val instanceChangeMap: MutableMap<String, FirstChangeData> = mutableMapOf()

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val sourceIdentifier = tscInstance.sourceIdentifier
    val existingChange =
        instanceChangeMap.getOrPut(sourceIdentifier) { FirstChangeData(changedFrom = tscInstance) }
    // If there is already a change recorded, do nothing
    if (existingChange.firstChangeAfterXUnits != null) return

    // If the TSC instance has changed, record the change
    if (existingChange.changedFrom != tscInstance) {
      instanceChangeMap[sourceIdentifier] =
          FirstChangeData(
              changedFrom = existingChange.changedFrom,
              changedTo = tscInstance,
              firstChangeAfterXUnits = tick.currentTickUnit)
    }
  }

  /**
   * Returns the state of the metric as a map.
   * - Map<sourceIdentifier,Map<TSCInstance (changedFrom), TSCInstance (changedTo),
   *   firstChangeAfterXMilliseconds>>.
   */
  override fun getState(): MutableMap<String, FirstChangeData> = instanceChangeMap

  /** Prints the time it took for the first TSC instance change for each source identifier. */
  override fun printState() {
    println(
        "\n$CONSOLE_SEPARATOR\n$CONSOLE_INDENT TSC Instance Change in Milliseconds \n$CONSOLE_SEPARATOR")
    val mean = getMeanTimeToFirstChange()
    logInfo("Mean Time to First Change: $mean ms\n")
    logInfo(
        "Standard Deviation of Time to First Change: ${getStandardDeviationTimeToFirstChange(mean)} ms\n")
    instanceChangeMap.forEach { (sourceIdentifier, firstChangeData) ->
      logInfo("Source Identifier: $sourceIdentifier\n")
      logInfo("$CONSOLE_INDENT First Change After: ${firstChangeData.firstChangeAfterXUnits}\n")
      logFine("$CONSOLE_INDENT Changed From Instance: ${firstChangeData.changedFrom.rootNode}\n")
      logFine("$CONSOLE_INDENT Changed To Instance: ${firstChangeData.changedFrom.rootNode}\n")
      logInfo(CONSOLE_SEPARATOR)
    }
  }

  /** Calculates the mean time to first change across all TSC instances. */
  private fun getMeanTimeToFirstChange(): Double {
    val totalTime =
        instanceChangeMap.values.mapNotNull { it.firstChangeAfterXUnits }.sumOf { it.tickMillis }
    return if (instanceChangeMap.isNotEmpty()) {
      totalTime.toDouble() / instanceChangeMap.size
    } else {
      0.0
    }
  }

  /** Calculates the standard deviation of time to first change across all TSC instances. */
  private fun getStandardDeviationTimeToFirstChange(mean: Double): Double {
    val variance =
        instanceChangeMap.values
            .mapNotNull { it.firstChangeAfterXUnits }
            .map { it.tickMillis }
            .sumOf { (it - mean) * (it - mean) } / instanceChangeMap.size
    return kotlin.math.sqrt(variance)
  }

  override fun writePlots() {
    val barPlotName = "firstTSCInstanceChangeBarPlot"

    val legendEntry = "Time to First Change (ms)"

    val yValues =
        instanceChangeMap.values
            .mapNotNull { it.firstChangeAfterXUnits }
            .map { it.tickMillis.toDouble() }
            .sorted()

    val plot =
        getPlot(
            legendEntry = legendEntry,
            yValues = yValues,
            xAxisName = "TSC Instances",
            yAxisName = "Time (ms)",
            legendHeader = "First TSC Instance Change")

    plotDataAsBarChart(
        plot = plot, fileName = barPlotName, folder = "firstTSCInstanceChangeBarPlot")
  }

  override fun writePlotDataCSV() {
    TODO("Not yet implemented")
  }
}
