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
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.selectAll
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.utils.everyNth
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** This utility evaluates the killed mutants per scenario configuration per monitor. */
object KilledMutantsPerMonitorPerScenarioPostEvaluation {

  /** Executes the evaluation and writes CSV and TeX files to [POST_EVALUATION_BASE_DIR]. */
  fun evaluate() {
    DbBootstrap.connect()
    db {
      val mfm = MetricFailedMonitorsTable
      val all = MetricStartingValidTSCInstancesTable

      val monitors: List<Pair<String, Column<Boolean>>> =
          listOf(
              "g0Accidents" to mfm.monitorG0Failed,
              "g1SafeDistanceToPrecedingVehicle" to mfm.monitorG1Failed,
              "g2UnnecessaryBraking" to mfm.monitorG2Failed,
              "g22AbruptBraking" to mfm.monitorG22Failed,
              "g3MaximumSpeedLimit" to mfm.monitorG3Failed,
              "g4TrafficFlow" to mfm.monitorG4Failed,
              "i1Stopping" to mfm.monitorI1Failed,
              "i2DrivingFasterThenLeftTraffic" to mfm.monitorI2Failed,
              "i3RightOvertaking" to mfm.monitorI3Failed,
              "i4KeepRight" to mfm.monitorI4Failed,
          )

      val allScenarios: List<UUID> =
          all.selectAll().withDistinct().orderBy(all.scenarioConfig, SortOrder.ASC).map {
              row: org.jetbrains.exposed.sql.ResultRow ->
            row[all.scenarioConfig].value
          }

      monitors.forEach { (rawMonitorName, failedColumn) ->
        val monitorName = sanitizeForPath(rawMonitorName)

        val killedMutants = mfm.mutant.countDistinct().alias("killed_mutants")

        val counts: Map<UUID, Long> =
            mfm.select(mfm.startingScenarioConfiguration, killedMutants)
                .where { failedColumn eq true }
                .groupBy(mfm.startingScenarioConfiguration)
                .associate { row ->
                  val scenarioId: UUID = row[mfm.startingScenarioConfiguration].value
                  val count: Long = row[killedMutants]
                  scenarioId to count
                }

        val points: List<Pair<UUID, Long>> =
            allScenarios
                .map { scId -> scId to (counts[scId] ?: 0L) }
                .sortedByDescending { it.second }

        writeResultFiles(monitorName, points)
      }
    }
  }

  private fun writeResultFiles(monitorName: String, points: List<Pair<UUID, Long>>) {
    val folder = POST_EVALUATION_BASE_DIR
    val subfolder = "killed_mutants_per_monitor_per_scenario"

    listOf(1, 20, 50).forEach { everyNthEntry ->
      val everyNThSubfolder =
          when (everyNthEntry) {
            1 -> "full"
            2 -> "every_${everyNthEntry}nd_entry"
            else -> "every_${everyNthEntry}th_entry"
          }

      val csvFileName = "$subfolder-$everyNThSubfolder-$monitorName.csv"
      val texFileName = "$subfolder-$everyNThSubfolder-$monitorName.tex"
      val largeTexFileName = "$subfolder-$everyNThSubfolder-$monitorName-large.tex"
      val plotName = "$subfolder-$everyNThSubfolder-$monitorName.png"

      val csvPath = Path.of(folder, subfolder, monitorName, everyNThSubfolder, csvFileName)
      val texPath = Path.of(folder, subfolder, monitorName, everyNThSubfolder, texFileName)
      val largeTexPath =
          Path.of(folder, subfolder, monitorName, everyNThSubfolder, largeTexFileName)
      val plotPath = Path.of(folder, subfolder, monitorName, everyNThSubfolder)

      Files.createDirectories(csvPath.parent)

      val filteredPoints = points.everyNth(everyNthEntry)
      csvPath.writeText(buildCSVString(filteredPoints))
      texPath.writeText(buildTexString(csvFileName, everyNthEntry, monitorName))
      largeTexPath.writeText(buildLargeTexString(csvFileName, everyNthEntry, monitorName))
      val plot =
          getPlot(
              legendHeader = "Killed Mutants per Scenario",
              xValues = List(filteredPoints.size) { it },
              yValues = filteredPoints.map { it.second },
              legendEntry = "Mutants")
      checkNotNull(plot) { "Plot could not be created: $subfolder." }
      plotDataAsBarChart(plot, fileName = plotName, path = plotPath)
    }
  }

  private fun buildTexString(csvFileName: String, everyNthEntry: Int, monitorName: String) =
      """
\documentclass[tikz,border=5pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\usepackage{pgfplotstable}

\begin{document}

    \pgfplotstableread[col sep=comma]{${csvFileName}}\datatable

    \begin{tikzpicture}
        \begin{axis}[
            ybar,
            bar width=1pt,
            title={Killed Mutants per Scenario Configuration (Monitor: ${monitorName}, every $${everyNthEntry}^{th}$ entry)},
            xlabel={Scenario Configuration},
            ylabel={Killed Mutants},
            enlarge x limits=false,
            ymin=0,
            xtick distance=2000,
            xticklabel style={rotate=45, anchor=east},
            scaled y ticks=false,
        ]
            \addplot+[ybar, draw=., fill=.]
            table[x expr=\coordindex, y=count]{\datatable};
        \end{axis}
    \end{tikzpicture}

\end{document}
      """
          .trimIndent()

  private fun buildLargeTexString(csvFileName: String, everyNthEntry: Int, monitorName: String) =
      """
\documentclass[tikz,border=5pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\usepackage{pgfplotstable}

\begin{document}

    \pgfplotstableread[col sep=comma]{${csvFileName}}\datatable

    \begin{tikzpicture}
        \begin{axis}[
            ybar,
            width=80cm,
            bar width=2pt,
            title={Killed Mutants per Scenario Configuration (Monitor: ${monitorName}, every $${everyNthEntry}^{th}$ entry)},
            xlabel={Scenario Configuration},
            ylabel={Killed Mutants},
            ymin=0,
            xtick=data,
            xticklabel style={rotate=45, anchor=east},
            xticklabels from table={\datatable}{scenarioConfigId},
            enlarge x limits=false,
            tick label style={font=\small},
            scaled y ticks=false,
        ]
            \addplot+[ybar, draw=., fill=.]
            table[x expr=\coordindex, y=count]{\datatable};
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

  private fun sanitizeForPath(raw: String): String =
      raw.trim().replace(Regex("""[^\w.\-]+"""), "_").replace(Regex("""_+"""), "_").trim('_')
}
