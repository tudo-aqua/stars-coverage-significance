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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.pos.positionDodge
import org.jetbrains.letsPlot.sampling.samplingNone
import org.jetbrains.letsPlot.scale.scaleXContinuous
import org.jetbrains.letsPlot.scale.scaleXLog10
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.scale.scaleYLog10

/**
 * Saves a PNG file with a bar chart based on the given values.
 *
 * @param plot Contains all necessary data points for the bar chart.
 * @param fileName The name of the resulting plot file.
 * @param path The path in which the plot should be saved.
 * @param size (Default: null) The size of the resulting PNG file.
 * @param xAxisScaleMaxValue (Default: null) Sets the max x-value for the chart. The data is scaled
 *   accordingly.
 * @param yAxisScaleMaxValue (Default: null) Sets the max y-value for the chart. The data is scaled
 *   accordingly.
 * @param logScaleX (Default: false) If true, the x-axis will be scaled logarithmically.
 * @param logScaleY (Default: false) If true, the y-axis will be scaled logarithmically.
 */
fun plotDataAsBarChart(
    plot: Plot,
    fileName: String,
    path: Path,
    size: Pair<Number, Number>? = null,
    xAxisScaleMaxValue: Number? = null,
    yAxisScaleMaxValue: Number? = null,
    logScaleX: Boolean = false,
    logScaleY: Boolean = false,
) =
    ggsave(
        plot =
            applyStyle(plot, size, xAxisScaleMaxValue, yAxisScaleMaxValue, logScaleX, logScaleY) +
                geomBar(stat = Stat.identity, position = positionDodge(), sampling = samplingNone),
        filename = fileName,
        path = path.absolutePathString(),
    )

/**
 * Creates a [Plot] object from [plot] containing features for plot size and x-y-axis scale and
 * ranges.
 *
 * @param plot Contains all necessary data points for the histogram.
 * @param size (Default: null) The size of the resulting PNG file.
 * @param xAxisScaleMaxValue (Default: null) Sets the max x-value for the chart. The data is scaled
 *   accordingly.
 * @param yAxisScaleMaxValue (Default: null) Sets the max y-value for the chart. The data is scaled
 *   accordingly.
 * @param logScaleX (Default: false) If true, the x-axis will be scaled logarithmically.
 * @param logScaleY (Default: false) If true, the y-axis will be scaled logarithmically.
 */
private fun applyStyle(
    plot: Plot,
    size: Pair<Number, Number>?,
    xAxisScaleMaxValue: Number?,
    yAxisScaleMaxValue: Number?,
    logScaleX: Boolean,
    logScaleY: Boolean,
): Plot {
  var innerPlot = plot

  // Set size
  if (size != null) innerPlot += ggsize(size.first, size.second)

  // Set x axis
  if (logScaleX) {
    innerPlot +=
        if (xAxisScaleMaxValue != null)
            scaleXLog10(limits = -0.001 to xAxisScaleMaxValue, expand = listOf(0, 0))
        else scaleXLog10(expand = listOf(0, 0))
  } else {
    if (xAxisScaleMaxValue != null)
        innerPlot += scaleXContinuous(limits = -0.001 to xAxisScaleMaxValue, expand = listOf(0, 0))
  }

  // Set y axis
  if (logScaleY) {
    innerPlot +=
        if (yAxisScaleMaxValue != null)
            scaleYLog10(limits = -0.001 to yAxisScaleMaxValue, expand = listOf(0, 0))
        else scaleYLog10(expand = listOf(0, 0))
  } else {
    if (yAxisScaleMaxValue != null)
        innerPlot += scaleYContinuous(limits = -0.001 to yAxisScaleMaxValue, expand = listOf(0, 0))
  }

  return innerPlot
}
