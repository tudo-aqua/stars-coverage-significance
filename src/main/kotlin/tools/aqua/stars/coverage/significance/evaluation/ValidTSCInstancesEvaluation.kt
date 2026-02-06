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

package tools.aqua.stars.coverage.significance.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.core.utils.plotDataAsBarChart
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository

object ValidTSCInstancesEvaluation {
  fun evaluate() {
    DbBootstrap.connect()
    val validStartingTSCInstances = MetricStartingValidTSCInstancesRepository.getAll()
    val groupedByTSCInstance = validStartingTSCInstances.groupingBy { it.tscInstanceId }.eachCount()
    val orderedCounts = groupedByTSCInstance.values.sortedDescending()

    val csvPath = Path.of("post-evaluation", "ordered_counts.csv")
    val texPath = Path.of("post-evaluation", "ordered_counts_plot.tex")

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
    plotDataAsBarChart(plot, "ordered_counts", "post-evaluation")
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
    // Use forward slashes to keep LaTeX happy on Windows too.
    val csvForLatex = csvPath.toString().replace('\\', '/')

    val tex =
        """
        \documentclass[tikz,border=5pt]{standalone}
        \usepackage{pgfplots}
        \pgfplotsset{compat=1.18}

        \begin{document}
        \begin{tikzpicture}
        \begin{axis}[
            ybar,
            bar width=3pt,
            title={${escapeLatex(title)}},
            xlabel={${escapeLatex(xLabel)}},
            ylabel={${escapeLatex(yLabel)}},
            enlargelimits=0.02,
            ymin=0,
            xtick=data,
            xticklabel style={rotate=90, anchor=east},
            tick label style={font=\small},
            % If there are many bars, showing every x tick can be heavy. Optional:
            % x tick label as interval=false,
        ]
        \addplot table[
            col sep=comma,
            x=index,
            y=count
        ] {${escapeLatex(csvForLatex)}};
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
