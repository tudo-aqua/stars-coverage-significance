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

package tools.aqua.stars.coverage.significance.utils

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomTile
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillGradient
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme

/**
 * Saves a PNG heatmap where rows are "from" labels, columns are "to" labels, and cell colour
 * encodes [value].
 *
 * @param fromLabels Ordered axis labels for the rows (source instances).
 * @param toLabels Ordered axis labels for the columns (destination instances).
 * @param values Matrix of cell values in row-major order: `values[fromIndex][toIndex]`.
 * @param fileName Output filename (should end in `.png`).
 * @param path Directory to write the file into (created if absent).
 * @param title Optional plot title.
 * @param subtitle Optional plot subtitle.
 * @param xLabel X-axis label (destination / "to" axis).
 * @param yLabel Y-axis label (source / "from" axis).
 * @param lowColor Colour for low values (default: white).
 * @param highColor Colour for high values (default: dark blue).
 * @param size Width × height in pixels.
 */
fun plotDataAsHeatmap(
    fromLabels: List<String>,
    toLabels: List<String>,
    values: Array<LongArray>,
    fileName: String,
    path: Path,
    title: String? = null,
    subtitle: String? = null,
    xLabel: String = "To",
    yLabel: String = "From",
    lowColor: String = "#ffffff",
    highColor: String = "#08306b",
    size: Pair<Number, Number> = 1200 to 1000,
) {
  Files.createDirectories(path)

  val fromList = mutableListOf<String>()
  val toList = mutableListOf<String>()
  val valueList = mutableListOf<Long>()

  for ((fi, from) in fromLabels.withIndex()) {
    for ((ti, to) in toLabels.withIndex()) {
      fromList += from
      toList += to
      valueList += values[fi][ti]
    }
  }

  val data =
      mapOf<String, Any>(
          "from" to fromList,
          "to" to toList,
          "value" to valueList,
      )

  var plot =
      letsPlot(data) { x = "to"; y = "from"; fill = "value" } +
          geomTile() +
          scaleFillGradient(low = lowColor, high = highColor, name = "Count") +
          labs(x = xLabel, y = yLabel) +
          theme(axisTextX = elementText(angle = 45.0, hjust = 1.0))

  if (title != null) {
    plot += ggtitle(title)
    if (subtitle != null) plot += labs(subtitle = subtitle)
  }

  plot += ggsize(size.first, size.second)

  ggsave(plot = plot, filename = fileName, path = path.absolutePathString())
}
