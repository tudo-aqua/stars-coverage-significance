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
import kotlin.to
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.sum
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG0Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG1Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG2Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG3Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG4Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorI1Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorI2Failed
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.utils.boolToInt
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** Counts the number of scenarios where a monitor failed. */
object CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation {

  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    println(
        "Start with CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.")
    DbBootstrap.connect()
    val result = scenarioCountWithAtLeastOneFailedMonitor()
    writeResultFiles(result)
    println(
        "Finished CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.")
  }

  private fun scenarioCountWithAtLeastOneFailedMonitor(): List<Pair<String, Map<String, Int>>> =
      db {
        val monitorG0SumAlias: Sum<Int> = boolToInt(monitorG0Failed).sum()
        val monitorG1SumAlias: Sum<Int> = boolToInt(monitorG1Failed).sum()
        val monitorG2SumAlias: Sum<Int> = boolToInt(monitorG2Failed).sum()
        val monitorG3SumAlias: Sum<Int> = boolToInt(monitorG3Failed).sum()
        val monitorG4SumAlias: Sum<Int> = boolToInt(monitorG4Failed).sum()
        val monitorI1SumAlias: Sum<Int> = boolToInt(monitorI1Failed).sum()
        val monitorI2SumAlias: Sum<Int> = boolToInt(monitorI2Failed).sum()

        val metricFailedMonitorsTable = MetricFailedMonitorsTable
        val joined =
            metricFailedMonitorsTable.join(
                MutantsTable,
                JoinType.LEFT,
                additionalConstraint = { metricFailedMonitorsTable.mutant eq MutantsTable.id })

        joined
            .select(
                MutantsTable.className,
                monitorG0SumAlias,
                monitorG1SumAlias,
                monitorG2SumAlias,
                monitorG3SumAlias,
                monitorG4SumAlias,
                monitorI1SumAlias,
                monitorI2SumAlias)
            .groupBy(MutantsTable.className)
            .map { row ->
              row[MutantsTable.className] to
                  mapOf(
                      monitorG0Failed.name to (row[monitorG0SumAlias] ?: -1),
                      monitorG1Failed.name to (row[monitorG1SumAlias] ?: -1),
                      monitorG2Failed.name to (row[monitorG2SumAlias] ?: -1),
                      monitorG3Failed.name to (row[monitorG3SumAlias] ?: -1),
                      monitorG4Failed.name to (row[monitorG4SumAlias] ?: -1),
                      monitorI1Failed.name to (row[monitorI1SumAlias] ?: -1),
                      monitorI2Failed.name to (row[monitorI2SumAlias] ?: -1))
            }
      }

  private fun writeResultFiles(
      points: List<Pair<String, Map<String, Int>>>,
  ) {
    points.forEach { (mutantClassName, countOfFailedScenarioInstancesPerMonitorMap) ->
      val folder = POST_EVALUATION_BASE_DIR
      val subfolder = "count_of_scenarios_where_monitors_failed_per_monitor"
      val subSubFolder = mutantClassName
      val csvFileName = "${subfolder}.csv"
      val texFileName = "${subfolder}.tex"
      val plotName = "${subfolder}.png"

      val csvPath = Path.of(folder, subfolder, subSubFolder, csvFileName)
      val texPath = Path.of(folder, subfolder, subSubFolder, texFileName)
      val plotPath = Path.of(folder, subfolder, subSubFolder)

      Files.createDirectories(csvPath.parent)

      val csv = buildCSVString(countOfFailedScenarioInstancesPerMonitorMap)
      csvPath.writeText(csv)
      val tex = buildTexString(csvFileName)
      texPath.writeText(tex)

      val x =
          points
              .mapIndexed { index, (mutantName, count) ->
                mutantName to listOf(index) to listOf(count)
              }
              .toMap()

      val plot =
          getPlot(
              nameToValuesMap =
                  mapOf(
                      mutantClassName to
                          (List(countOfFailedScenarioInstancesPerMonitorMap.size) { it } to
                              countOfFailedScenarioInstancesPerMonitorMap.values.toList())),
              xAxisName = "Monitor",
              yAxisName = "#TSC instances where monitor failed",
              legendHeader = "Legend")
      checkNotNull(plot) { "Plot could not be created: $subfolder." }
      plotDataAsBarChart(
          plot,
          fileName = plotName,
          path = plotPath,
          title = "Number of TSC instances where monitor failed")
    }
  }

  private fun buildCSVString(sortedCounts: Map<String, Int?>) = buildString {
    appendLine("monitor,scenario_instances_where_monitor_failed")
    for ((mutant, count) in sortedCounts) appendLine("$mutant,${count}")
  }

  private fun buildTexString(csvFileName: String) =
      """
\documentclass[tikz,border=5pt]{standalone}
\usepackage{pgfplots}
\usepackage{pgfplotstable}
\pgfplotsset{compat=1.18}

\begin{document}

\pgfplotstableread[col sep=comma]{${csvFileName}}\datatable
\pgfplotstablegetrowsof{\datatable}
\pgfmathtruncatemacro{\LastRow}{\pgfplotsretval-1}

\begin{tikzpicture}
\begin{axis}[
  width=16cm,
  bar width=8pt,
  ymin=0,
  ylabel={\#TSC instances where monitor failed},
  xlabel={Monitor},
  scaled y ticks=false,
  xtick=data,
  x tick label as interval=false,
  xticklabels from table={\datatable}{monitor},
  xticklabel style={rotate=45, anchor=east, font=\scriptsize},
]

% Bars (fill = draw color)
\addplot+[
  ybar,
  draw=blue,
  fill=.,
  mark=none
] table[
  x expr=\coordindex,
  y=count
]{\datatable};

% Baseline y = 160 (available TSC instances)
\addplot+[
  red,
  very thick,
  mark=none
] coordinates {(0,160) (\LastRow,160)};

\end{axis}
\end{tikzpicture}

\end{document}
  """
}
