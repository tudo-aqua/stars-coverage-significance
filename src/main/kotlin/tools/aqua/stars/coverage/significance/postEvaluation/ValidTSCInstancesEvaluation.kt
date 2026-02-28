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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.core.utils.plotDataAsBarChart
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository

/**
 * This utility evaluates the valid TSC instances metric by counting how many valid TSC instances
 * there are per TSC instance ID, ordering the counts, and writing the results to a CSV and a LaTeX
 * pgfplots file for plotting. It also generates a bar chart using the STARS core plotting
 * utilities.
 */
object ValidTSCInstancesEvaluation {
  /**
   * Main entry point for the evaluation. Connects to the database, retrieves valid TSC instances,
   * counts them per TSC instance ID, orders the counts, and writes the results to CSV and LaTeX
   * files, as well as generating a bar chart.
   */
  fun evaluate() {
    DbBootstrap.connect()
    val validStartingTSCInstances = MetricStartingValidTSCInstancesRepository.getAll()
    val groupedByTSCInstance = validStartingTSCInstances.groupingBy { it.tscInstanceId }.eachCount()
    val orderedCounts = groupedByTSCInstance.values.sortedDescending()

    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "valid_tsc_instances"

    val csvPath = Path.of(folder, subfolder, "ordered_counts.csv")
    val texPath = Path.of(folder, subfolder, "ordered_counts_plot.tex")

    writeOrderedCountsCsv(orderedCounts, csvPath)
    writePgfplotsBarChartTex(
        csvPath = csvPath,
        texPath = texPath,
        title = "Ordered counts per TSC instance",
        xLabel = "Index",
        yLabel = "Count")

    val plot =
        getPlot(
            "Instance Count",
            xValues = orderedCounts.mapIndexed { index, _ -> index },
            yValues = orderedCounts)
    plotDataAsBarChart(plot, "ordered_counts", folder)
  }

  /** Writes a CSV with columns: index,count index starts at 1 (change to 0 if you prefer). */
  fun writeOrderedCountsCsv(orderedCounts: List<Int>, csvPath: Path) {
    val sb = StringBuilder()
    sb.appendLine("index,count")
    orderedCounts.forEachIndexed { i, count -> sb.appendLine("${i + 1},$count") }
    Files.createDirectories(csvPath.parent)
    Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8)
  }

  /**
   * Writes a standalone LaTeX file that uses pgfplots to plot the CSV as a bar plot.
   *
   * Compile with: pdflatex ordered_counts_plot.tex
   *
   * The plot reads columns index,count from the CSV.
   */
  fun writePgfplotsBarChartTex(
      csvPath: Path,
      texPath: Path,
      title: String = "Bar plot",
      xLabel: String = "Index",
      yLabel: String = "Count"
  ) {
    val csvForLatex = csvPath.fileName.toString()

    val tex =
        """
        \documentclass[tikz,border=5pt]{standalone}
        \usepackage{pgfplots}
        \pgfplotsset{compat=1.18}

        \begin{document}
        \begin{tikzpicture}
        \begin{axis}[
            ybar,
            bar width=1pt,
            title={${escapeLatex(title)}},
            xlabel={${escapeLatex(xLabel)}},
            ylabel={${escapeLatex(yLabel)}},
            enlargelimits=0.02,
            ymin=0,
            xtick distance=50,
            xticklabel style={rotate=90, anchor=east},
            tick label style={font=\small},
            scaled y ticks=false,
        ]
        \addplot+[ybar,
        draw=.,
        fill=.,
        ] table[
            col sep=comma,
            x=index,
            y=count
        ] {$csvForLatex};
        \end{axis}
        \end{tikzpicture}
        \end{document}
    """
            .trimIndent()

    Files.createDirectories(csvPath.parent)
    Files.writeString(texPath, tex, StandardCharsets.UTF_8)
  }

  /**
   * Minimal LaTeX escaping for titles/labels/paths. Extend if you plan to include lots of special
   * characters.
   */
  fun escapeLatex(s: String): String =
      s.replace("\\", "\\textbackslash{}")
          .replace("_", "\\_")
          .replace("%", "\\%")
          .replace("&", "\\&")
          .replace("#", "\\#")
          .replace("{", "\\{")
          .replace("}", "\\}")
          .replace("$", "\\$")
          .replace("^", "\\^{}")
          .replace("~", "\\~{}")
}
