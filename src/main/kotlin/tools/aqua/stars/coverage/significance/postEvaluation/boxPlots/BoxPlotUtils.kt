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

import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import org.jetbrains.letsPlot.core.plot.base.stat.AggregateFunctions.median

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
