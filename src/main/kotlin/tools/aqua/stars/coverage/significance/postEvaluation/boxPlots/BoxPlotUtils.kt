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

package tools.aqua.stars.coverage.significance.postEvaluation.boxPlots

import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.letsPlot.core.plot.base.stat.AggregateFunctions.median

suspend fun createBoxPlot(
    metricName: String,
    scenarioFailures: List<ScenarioFailure>,
    repetitions: Int = 500,
    selectedMonitors: Set<MonitorViolation>,
    baseSeed: Long,
    relevantMutants: List<UUID>
) {
  val allScenarios = scenarioFailures.map { it.scenarioId }
  //    val coverageList = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 40, 80, 160)

  val coverageList = List(allScenarios.size) { it + 1 }
  val boxPlotData: Map<Int, BoxPlotData> = coroutineScope {
    coverageList
        .map { coverage ->
          async(Dispatchers.Default) {
            coverage to
                evaluateCoverage(
                    scenarioFailures = scenarioFailures,
                    allScenarios = allScenarios,
                    coverage = coverage,
                    repetitions = repetitions,
                    selectedMonitors = selectedMonitors,
                    seed = baseSeed * 10_000 + coverage,
                    relevantMutants = relevantMutants)
          }
        }
        .awaitAll()
        .toMap()
  }

  writeCSVAndTeXFiles(
      metricName = metricName,
      map = boxPlotData,
      selectedMonitors = selectedMonitors,
      numberOfMutants = relevantMutants.size)
}

private fun evaluateCoverage(
    scenarioFailures: List<ScenarioFailure>,
    allScenarios: List<UUID>,
    coverage: Int,
    repetitions: Int,
    selectedMonitors: Set<MonitorViolation>,
    seed: Long,
    relevantMutants: List<UUID>
): BoxPlotData {
  val countOfKilledMutants = MutableList(repetitions) { 0 }
  val countOfFailedMonitors = MutableList(repetitions) { 0 }
  val countOfDistinctMonitorsFailed = MutableList(repetitions) { 0 }

  repeat(repetitions) { repetition ->
    val rng = Random(seed + repetition)

    val repetitionScenarioIds = allScenarios.drawRandomElements(coverage, rng)
    val drawnScenarios = scenarioFailures.filter { it.scenarioId in repetitionScenarioIds }

    val drawnScenarioInstances = drawnScenarios.map { it.scenarioInstanceFailures.random(rng) }

    val relevantMonitors =
        drawnScenarioInstances.flatMap { scenarioInstance ->
          scenarioInstance.mutants.filter { mutant ->
            mutant.mutantId in relevantMutants && mutant.violations.any { it in selectedMonitors }
          }
        }

    val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()
    countOfKilledMutants[repetition] = mutantsKilled

    val monitorsFailed = relevantMonitors.flatMap { it.violations }.count()
    countOfFailedMonitors[repetition] = monitorsFailed

    val distinctMonitorsFailed = relevantMonitors.flatMap { it.violations }.toSet().count()
    countOfDistinctMonitorsFailed[repetition] = distinctMonitorsFailed
  }

  return BoxPlotData(
      countOfKilledMutants = countOfKilledMutants,
      countOfFailedMonitors = countOfFailedMonitors,
      countOfDistinctMonitorsFailed = countOfDistinctMonitorsFailed)
}

fun List<Double>.nTile(n: Double): Double {
  val sortedList = this.sorted()
  val position = sortedList.size * n
  return if (position % 1 != 0.0) {
    (sortedList[floor(position).toInt()] + sortedList[ceil(position).toInt()]) / 2
  } else {
    sortedList[position.toInt()]
  }
}

fun <T> Collection<T>.drawRandomElements(x: Int, rng: Random): List<T> {
  require(x >= 0) { "x must be non-negative" }
  require(x <= size) { "x must not be larger than list size" }
  return shuffled(rng).take(x)
}

fun List<Number>.getBoxPlotValues(): BoxPlotValues {
  val sortedDoubleList = this.map { it.toDouble() }.sorted()
  val firstQuartile = sortedDoubleList.nTile(0.25)
  val thirdQuartile = sortedDoubleList.nTile(0.75)

  val iqr = thirdQuartile - firstQuartile
  val lowerWhisker = maxOf(sortedDoubleList.min(), firstQuartile - 1.5 * iqr)
  val upperWhisker = minOf(sortedDoubleList.max(), thirdQuartile + 1.5 * iqr)
  val lowerMildOutlierBound = lowerWhisker - (1.5 * iqr)
  val upperMildOutlierBound = upperWhisker + (1.5 * iqr)

  val mildOutliers =
      sortedDoubleList
          .filter {
            it in lowerMildOutlierBound..lowerWhisker || it in upperWhisker..upperMildOutlierBound
          }
          .distinct()
  val extremeOutliers =
      sortedDoubleList.filter { it !in lowerMildOutlierBound..upperMildOutlierBound }.distinct()

  return BoxPlotValues(
      median = median(sortedDoubleList),
      firstQuartile = firstQuartile,
      thirdQuartile = thirdQuartile,
      lowerWhisker = lowerWhisker,
      upperWhisker = upperWhisker,
      mildOutliers = mildOutliers,
      extremeOutliers = extremeOutliers,
      allData = sortedDoubleList)
}

fun Map<Int, BoxPlotValues>.getBoxPlotCSVString() =
    this.map { (coverage, boxPlotValues) ->
          "${coverage}, ${boxPlotValues.median}, ${boxPlotValues.firstQuartile}, ${boxPlotValues.thirdQuartile}, ${boxPlotValues.lowerWhisker}, ${boxPlotValues.upperWhisker}"
        }
        .joinToString(
            separator = "\n", prefix = "coverage, median, firstQuartile, thirdQuartile, min, max\n")

fun Map<Int, BoxPlotValues>.getFullDataCSVString() =
    this.map { (coverage, boxPlotValues) ->
          "${coverage}, ${boxPlotValues.allData.joinToString(",")}"
        }
        .joinToString(separator = "\n", prefix = "coverage, dataPoints\n")

fun Map<Int, BoxPlotValues>.getMildOutliersCSVString() =
    this.flatMap { (coverage, boxPlotValues) ->
          boxPlotValues.mildOutliers.map { "${coverage}, $it" }
        }
        .joinToString(separator = "\n", prefix = "coverage, outlier\n")

fun Map<Int, BoxPlotValues>.getExtremeOutliersCSVString() =
    this.flatMap { (coverage, boxPlotValues) ->
          boxPlotValues.extremeOutliers.map { "${coverage}, $it" }
        }
        .joinToString(separator = "\n", prefix = "coverage, outlier\n")
