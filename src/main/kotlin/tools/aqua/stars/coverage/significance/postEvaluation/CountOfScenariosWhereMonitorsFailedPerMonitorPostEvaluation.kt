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
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
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
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorG5Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorI1Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorI2Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.monitorI3Failed
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.g0Accidents
import tools.aqua.stars.coverage.significance.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.g2UnnecessaryBraking
import tools.aqua.stars.coverage.significance.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.g4TrafficFlow
import tools.aqua.stars.coverage.significance.g5EmergencyBraking
import tools.aqua.stars.coverage.significance.i1Stopping
import tools.aqua.stars.coverage.significance.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.i3DangerousCutIn
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** Counts the number of scenarios where a monitor failed. */
object CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation {

  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    println("Start with CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.")
    DbBootstrap.connect()
    val result = evaluateAllMonitors().toList().sortedByDescending { it.second }
    writeResultFiles(result)
    println("Finished CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.")
  }

  private fun killingsPerTscInstance(monitorColumn: Column<Boolean>): Long = db {
    val failedMonitorsTable = MetricFailedMonitorsTable
    val startingValidTSCInstancesTable = MetricStartingValidTSCInstancesTable

    val joined =
        failedMonitorsTable.join(
            otherTable = startingValidTSCInstancesTable,
            joinType = JoinType.INNER,
            additionalConstraint = {
              failedMonitorsTable.startingScenarioConfiguration eq
                  startingValidTSCInstancesTable.scenarioConfig
            })

    joined
        .select(startingValidTSCInstancesTable.tscInstance)
        .where { monitorColumn eq true }
        .groupBy(startingValidTSCInstancesTable.tscInstance)
        .count()
  }

  private fun evaluateAllMonitors(): Map<String, Long> = db {
    val monitors: List<Pair<String, Column<Boolean>>> =
        listOf(
            g0Accidents.name to monitorG0Failed,
            g1SafeDistanceToPrecedingVehicle.name to monitorG1Failed,
            g2UnnecessaryBraking.name to monitorG2Failed,
            g3MaximumSpeedLimit.name to monitorG3Failed,
            g4TrafficFlow.name to monitorG4Failed,
            g5EmergencyBraking.name to monitorG5Failed,
            i1Stopping.name to monitorI1Failed,
            i2DrivingFasterThenLeftTraffic.name to monitorI2Failed,
            i3DangerousCutIn.name to monitorI3Failed)

    monitors.associate { (name, col) -> name to killingsPerTscInstance(col) }
  }

  private fun writeResultFiles(
      points: List<Pair<String, Long>>,
  ) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "count_of_scenarios_where_monitors_failed_per_monitor"
    val csvFileName = "${subfolder}.csv"
    val texFileName = "${subfolder}.tex"
    val plotName = "${subfolder}.png"

    val csvPath = Path.of(folder, subfolder, csvFileName)
    val texPath = Path.of(folder, subfolder, texFileName)
    val plotPath = Path.of(folder, subfolder)

    Files.createDirectories(csvPath.parent)

    val csv = buildCSVString(points)
    csvPath.writeText(csv)
    val tex = buildTexString(csvFileName)
    texPath.writeText(tex)

    val plot =
        getPlot(
            nameToValuesMap =
                points
                    .mapIndexed { index, (mutantName, count) ->
                      mutantName to (listOf(index) to listOf(count))
                    }
                    .toMap(),
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

  private fun buildCSVString(sortedCounts: List<Pair<String, Long>>) = buildString {
    appendLine("monitor,count")
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
