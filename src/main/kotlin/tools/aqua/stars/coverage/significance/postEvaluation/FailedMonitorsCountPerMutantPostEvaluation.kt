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
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.sum
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.utils.boolToInt
import tools.aqua.stars.coverage.significance.utils.everyNth

/**
 * This utility evaluates the failed monitors count per mutant.
 */
object FailedMonitorsCountPerMutantPostEvaluation {
  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    DbBootstrap.connect()
    db {
      val table =
          MetricFailedMonitorsTable.join(
              MutantsTable,
              JoinType.LEFT,
              onColumn = MetricFailedMonitorsTable.mutant,
              otherColumn = MutantsTable.id)

      // per row: number of failed monitors in that row
      val perRowCount: ExpressionWithColumnType<Int> =
          boolToInt(MetricFailedMonitorsTable.monitorG0Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorG1Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorG2Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorG22Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorG3Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorG4Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorI1Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorI2Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorI3Failed) +
              boolToInt(MetricFailedMonitorsTable.monitorI4Failed)

      // group aggregate: sum across all rows in the group
      val countAlias = perRowCount.sum().alias("count")

      val result =
          table
              .select(MutantsTable.id, MutantsTable.className, countAlias)
              .groupBy(MutantsTable.id)
              .orderBy(countAlias, SortOrder.DESC)

      val points =
          result
              .map { row -> row[MutantsTable.className] to (row[countAlias] ?: 0) }
              .sortedByDescending { it.second }
      writeResultFiles(points)
    }
  }

  private fun writeResultFiles(
      points: List<Pair<String, Int>>,
  ) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "failed_monitors_count_per_mutant"

    listOf(1).forEach { everyNthEntry ->
      val everyNThSubfolder = "every_${everyNthEntry}-th_entry"
      val csvFileName = "${subfolder}-every_${everyNthEntry}-th_entry.csv"
      val texFileName = "${subfolder}-every_${everyNthEntry}-th_entry.tex"
      val largeTexFileName = "${subfolder}-every_${everyNthEntry}-th_entry-large.tex"

      val csvPath = Path.of(folder, subfolder, everyNThSubfolder, csvFileName)
      val texPath = Path.of(folder, subfolder, everyNThSubfolder, texFileName)
      val largeTexPath = Path.of(folder, subfolder, everyNThSubfolder, largeTexFileName)

      Files.createDirectories(csvPath.parent)

      val csv = buildCSVString(points.everyNth(everyNthEntry))
      csvPath.writeText(csv)
      val tex = buildTexString(csvFileName, everyNthEntry)
      texPath.writeText(tex)
      val largeTex = buildLargeTexString(csvFileName, everyNthEntry)
      largeTexPath.writeText(largeTex)
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
            title={Failed Monitors per Mutant (every $${everyNthEntry}^{th}$ entry)},
            xlabel={Mutant},
            ylabel={Failed Monitors},
            enlarge x limits=false,
            ymin=0,
            xtick distance=50,
            xticklabel style={rotate=45, anchor=east},
            scaled y ticks=false,
            ]
            \addplot table[
            x expr=\coordindex,
            y=count,
            ]{\datatable};
        \end{axis}
    \end{tikzpicture}
    
\end{document}
    """

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
            title={Failed Monitors per Mutant (every $${everyNthEntry}^{th}$ entry)},
            xlabel={Mutant},
            ylabel={Failed Monitors},
            ymin=0,
            xtick=data,
            xticklabel style={rotate=45, anchor=east},
            xticklabels from table={\datatable}{mutantId},
            enlarge x limits=false,
            tick label style={font=\small},
            scaled y ticks=false,
            ]
            \addplot table[
            x expr=\coordindex,
            y=count,
            ]{\datatable};
        \end{axis}
    \end{tikzpicture}
    
\end{document}
    """

  private fun buildCSVString(data: List<Pair<String, Int>>): String = buildString {
    appendLine("mutantId,count")
    for (p in data) {
      append(p.first)
      append(',')
      append(p.second)
      appendLine()
    }
  }
}
