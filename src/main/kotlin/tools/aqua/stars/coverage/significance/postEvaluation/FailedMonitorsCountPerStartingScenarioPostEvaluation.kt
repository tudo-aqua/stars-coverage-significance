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
import kotlin.collections.sortedByDescending
import kotlin.io.path.writeText
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.sum
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.utils.boolToInt
import tools.aqua.stars.coverage.significance.utils.everyNth

/** This utility evaluates the failed monitors count per starting scenario configuration. */
object FailedMonitorsCountPerStartingScenarioPostEvaluation {

  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    DbBootstrap.connect()
    db {
      val t = MetricFailedMonitorsTable

      // per row: number of failed monitors in that row
      val perRowCount: ExpressionWithColumnType<Int> =
          boolToInt(t.monitorG0Failed) +
              boolToInt(t.monitorG1Failed) +
              boolToInt(t.monitorG2Failed) +
              boolToInt(t.monitorG22Failed) +
              boolToInt(t.monitorG3Failed) +
              boolToInt(t.monitorG4Failed) +
              boolToInt(t.monitorI1Failed) +
              boolToInt(t.monitorI2Failed) +
              boolToInt(t.monitorI3Failed) +
              boolToInt(t.monitorI4Failed)

      // group aggregate: sum across all rows in the group
      val countAlias = perRowCount.sum().alias("count")

      val result =
          t.select(t.startingScenarioConfiguration, countAlias)
              .groupBy(t.startingScenarioConfiguration)
              .orderBy(countAlias, SortOrder.DESC)

      val points =
          result
              .map { row -> row[t.startingScenarioConfiguration].value to (row[countAlias] ?: 0) }
              .sortedByDescending { it.second }
      writeResultFiles(points)
    }
  }

  private fun writeResultFiles(
      points: List<Pair<UUID, Int>>,
  ) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "failed_monitors_count_per_starting_scenario_config"

    listOf(1, 2, 5, 10).forEach { everyNthEntry ->
      val everyNThSubfolder = "every_${everyNthEntry}-th_entry"
      val csvFileName = "${subfolder}-every_${everyNthEntry}-th_entry.csv"
      val texFileName = "${subfolder}-every_${everyNthEntry}-th_entry.tex"

      val csvPath = Path.of(folder, subfolder, everyNThSubfolder, csvFileName)
      val texPath = Path.of(folder, subfolder, everyNThSubfolder, texFileName)

      Files.createDirectories(csvPath.parent)

      val csv = buildCSVString(points.everyNth(everyNthEntry))
      csvPath.writeText(csv)
      val tex = buildTexString(csvFileName, everyNthEntry)
      texPath.writeText(tex)
    }
  }

  private fun buildTexString(csvFileName: String, everyNthEntry: Int) =
      """
\documentclass[tikz,border=5pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\usepackage{pgfplotstable}

\begin{document}
  \begin{tikzpicture}
    \begin{axis}[
      ybar,
      bar width=1pt,
      title={Failed Monitors per Mutant (every $${everyNthEntry}^{th}$ entry)},
      xlabel={Mutant},
      ylabel={Failed Monitors},
      enlargelimits=0.02,
      ymin=0,
      xticklabel style={rotate=90, anchor=east},
      tick label style={font=\small},
      scaled y ticks=false,
      ]
    \addplot table[
        col sep=comma,
        x expr=\coordindex,
        y=count,
    ]{${csvFileName}};
    \end{axis}
  \end{tikzpicture}
\end{document}
    """

  private fun buildCSVString(data: List<Pair<UUID, Int>>): String = buildString {
    appendLine("scenarioConfigId,count")
    for (p in data) {
      append(p.first)
      append(',')
      append(p.second)
      appendLine()
    }
  }
}
