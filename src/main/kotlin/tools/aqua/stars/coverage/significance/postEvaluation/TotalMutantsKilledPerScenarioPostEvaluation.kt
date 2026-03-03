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

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.or
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.core.utils.plotDataAsBarChart
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.utils.everyNth
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** This utility evaluates the killed mutants per scenario configuration. */
object TotalMutantsKilledPerScenarioPostEvaluation {
  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    DbBootstrap.connect()
    db {
      val mfm = MetricFailedMonitorsTable

      val killedMutants = mfm.mutant.countDistinct().alias("killed_mutants")

      val result =
          mfm.select(mfm.startingScenarioConfiguration, killedMutants)
              .where {
                (mfm.monitorG0Failed eq true) or
                    (mfm.monitorG1Failed eq true) or
                    (mfm.monitorG2Failed eq true) or
                    (mfm.monitorG22Failed eq true) or
                    (mfm.monitorG3Failed eq true) or
                    (mfm.monitorG4Failed eq true) or
                    (mfm.monitorI1Failed eq true) or
                    (mfm.monitorI2Failed eq true) or
                    (mfm.monitorI3Failed eq true) or
                    (mfm.monitorI4Failed eq true)
              }
              .groupBy(mfm.startingScenarioConfiguration)
              .orderBy(killedMutants, SortOrder.DESC)

      val points: List<Pair<UUID, Long>> =
          result
              .map { row ->
                val scenarioConfigId: UUID = row[mfm.startingScenarioConfiguration].value
                val count: Long = row[killedMutants]
                scenarioConfigId to count
              }
              .sortedByDescending { it.second }

      writeResultFiles(points)
    }
  }

  private fun writeResultFiles(points: List<Pair<UUID, Long>>) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "total_mutants_killed_per_scenario"

    listOf(1, 20).forEach { everyNthEntry ->
      val everyNThSubfolder =
          when (everyNthEntry) {
            1 -> "full"
            2 -> "every_${everyNthEntry}nd_entry"
            else -> "every_${everyNthEntry}th_entry"
          }

      val csvFileName = "${subfolder}-$everyNThSubfolder.csv"
      val texFileName = "${subfolder}-$everyNThSubfolder.tex"
      val plotName = "${subfolder}-$everyNThSubfolder.png"

      val csvPath = Path.of(folder, subfolder, everyNThSubfolder, csvFileName)
      val texPath = Path.of(folder, subfolder, everyNThSubfolder, texFileName)
      val plotPath = Path.of(folder, subfolder, everyNThSubfolder)

      Files.createDirectories(csvPath.parent)

      val csv = buildCSVString(points.everyNth(everyNthEntry))
      csvPath.writeText(csv)

      val tex = buildTexString(csvFileName, everyNthEntry)
      texPath.writeText(tex)

      val plot =
          getPlot(
              legendEntry = "Mutants",
              xValues = List(points.size) { it },
              yValues = points.map { it.second })
      checkNotNull(plot) { "Plot could not be created: $subfolder." }
      plotDataAsBarChart(
          plot, fileName = plotName, path = plotPath, title = "Killed Mutants per Scenario")
    }
  }

  private fun buildTexString(csvFileName: String, everyNthEntry: Int) =
      """
\documentclass[tikz,border=5pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\usepackage{pgfplotstable}

\begin{document}

    % Read CSV once with the correct separator:
    \pgfplotstableread[col sep=comma]{${csvFileName}}\datatable

    \begin{tikzpicture}
        \begin{axis}[
            ybar,
            bar width=1pt,
            title={Killed Mutants per Scenario Configuration (every $${everyNthEntry}^{th}$ entry)},
            xlabel={Scenario Configuration},
            ylabel={Killed Mutants},
            enlarge x limits=false,
            ymin=0,
            xtick distance=50,
            xticklabel style={rotate=45, anchor=east},
            scaled y ticks=false,
            ]
            \addplot+[ybar,
            draw=.,
            fill=.,
            ] table[
            x expr=\coordindex,
            y=count,
            ]{\datatable};
        \end{axis}
    \end{tikzpicture}

\end{document}
      """
          .trimIndent()

  private fun buildCSVString(data: List<Pair<UUID, Long>>): String = buildString {
    appendLine("scenarioConfigId,count")
    for ((scenarioConfigId, count) in data) {
      append(scenarioConfigId)
      append(',')
      append(count)
      appendLine()
    }
  }
}
