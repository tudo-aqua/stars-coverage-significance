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
import kotlin.io.path.writeText
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.or
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.utils.everyNth
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** Post evaluation for failed scenarios with failed monitor per mutant. */
object TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation {

  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    println("Start with TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.")
    DbBootstrap.connect()
    db {
      val mfm = MetricFailedMonitorsTable

      val table =
          MetricFailedMonitorsTable.join(
              MutantsTable,
              JoinType.LEFT,
              onColumn = MetricFailedMonitorsTable.mutant,
              otherColumn = MutantsTable.id)

      val failedScenarioConfigCount =
          mfm.startingScenarioConfiguration.countDistinct().alias("failed_scenario_config_count")

      val result =
          table
              .select(MutantsTable.className, failedScenarioConfigCount)
              .where {
                (mfm.monitorG0Failed eq true) or
                    (mfm.monitorG1Failed eq true) or
                    (mfm.monitorG2Failed eq true) or
                    (mfm.monitorG3Failed eq true) or
                    (mfm.monitorG4Failed eq true) or
                    (mfm.monitorG5Failed eq true) or
                    (mfm.monitorI1Failed eq true) or
                    (mfm.monitorI2Failed eq true) or
                    (mfm.monitorI3Failed eq true)
              }
              .groupBy(MutantsTable.className)
              .orderBy(failedScenarioConfigCount, SortOrder.DESC)

      val points: List<Pair<String, Long>> =
          result
              .map { row -> row[MutantsTable.className] to row[failedScenarioConfigCount] }
              .sortedByDescending { it.second }

      writeResultFiles(points)
    }
    println("Finished TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.")
  }

  private fun writeResultFiles(points: List<Pair<String, Long>>) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "total_number_of_scenarios_with_at_least_one_failed_monitor_per_mutant"

    listOf(1).forEach { everyNthEntry ->
      val everyNThSubfolder =
          when (everyNthEntry) {
            1 -> "full"
            2 -> "every_${everyNthEntry}nd_entry"
            else -> "every_${everyNthEntry}th_entry"
          }

      val csvFileName = "${subfolder}-$everyNThSubfolder.csv"
      val texFileName = "${subfolder}-$everyNThSubfolder.tex"
      val largeTexFileName = "${subfolder}-$everyNThSubfolder-large.tex"
      val plotName = "${subfolder}-$everyNThSubfolder.png"

      val csvPath = Path.of(folder, subfolder, everyNThSubfolder, csvFileName)
      val texPath = Path.of(folder, subfolder, everyNThSubfolder, texFileName)
      val largeTexPath = Path.of(folder, subfolder, everyNThSubfolder, largeTexFileName)
      val plotPath = Path.of(folder, subfolder, everyNThSubfolder)

      Files.createDirectories(csvPath.parent)

      val csv = buildCSVString(points.everyNth(everyNthEntry))
      csvPath.writeText(csv)

      val tex = buildTexString(csvFileName, everyNthEntry)
      texPath.writeText(tex)

      val largeTex = buildLargeTexString(csvFileName, everyNthEntry)
      largeTexPath.writeText(largeTex)

      val plot =
          getPlot(
              legendEntry = "Scenarios",
              xValues = List(points.size) { index -> index },
              yValues = points.map { it.second })
      checkNotNull(plot) { "Plot could not be created: $subfolder." }
      plotDataAsBarChart(
          plot,
          fileName = plotName,
          path = plotPath,
          title = "Total Number of Scenarios with at least one Failed Monitor per Mutant")
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
    \pgfplotstablegetrowsof{\datatable}
    \pgfmathtruncatemacro{\LastRow}{\pgfplotsretval-1}

    \begin{tikzpicture}
        \begin{axis}[
            bar width=1pt,
            title={Scenarios with at least one Failed Monitor per Mutant (every $${everyNthEntry}^{th}$ entry)},
            xlabel={Mutant},
            ylabel={Scenario Configurations},
            enlarge x limits=false,
            ymin=0,
            xtick distance=50,
            xticklabel style={rotate=45, anchor=east},
            scaled y ticks=false,
        ]
        \addplot+[ybar,
            draw=.,
            fill=.,
            mark=none
        ] table[
            x expr=\coordindex,
            y=count,
        ]{\datatable};
        \addplot+[
          red,
          very thick,
          mark=none
        ] coordinates {(0,196608) (\LastRow,196608)};
        \end{axis}
    \end{tikzpicture}

\end{document}
    """
          .trimIndent()

  private fun buildLargeTexString(csvFileName: String, everyNthEntry: Int) =
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
            width=80cm,
            bar width=8pt,
            title={Scenario Configs with Failed Monitor per Mutant (every $${everyNthEntry}^{th}$ entry)},
            xlabel={Mutant},
            ylabel={Scenario Configurations},
            ymin=0,
            xtick=data,
            xticklabel style={rotate=45, anchor=east},
            xticklabels from table={\datatable}{mutantId},
            enlarge x limits=false,
            tick label style={font=\small},
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

  private fun buildCSVString(data: List<Pair<String, Long>>): String = buildString {
    appendLine("mutantId,count")
    for ((mutantId, count) in data) {
      append(mutantId)
      append(',')
      append(count)
      appendLine()
    }
  }
}
