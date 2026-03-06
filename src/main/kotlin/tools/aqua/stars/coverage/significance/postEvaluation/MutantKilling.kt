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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.letsPlot.core.plot.base.stat.AggregateFunctions.median
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

typealias ScenarioId = UUID

typealias ScenarioInstanceId = UUID

typealias MutantId = UUID

val RAND = Random(seed = 42)

enum class MonitorViolation {
  G0Accidents,
  G1SafeDistance,
  G2UnnecessaryBraking,
  G3MaximumSpeedLimit,
  G4TrafficFlow,
  G5EmergencyBraking,
  I1Stopping,
  I2FasterThanLeftTraffic,
  I3DangerousCutIn,
}

data class MutantFailures(val mutantId: MutantId, val violations: List<MonitorViolation>)

data class ScenarioInstanceFailures(
    val scenarioInstanceId: ScenarioInstanceId,
    val mutants: List<MutantFailures>
)

data class ScenarioFailure(
    val scenarioId: ScenarioId,
    val scenarios: List<ScenarioInstanceFailures>
)

data class BoxPlotData(
    val countOfKilledMutants: List<Int>,
    val normalizedCountOfKilledMutants: List<Double>,
    val countOfFailedMonitors: List<Int>,
    val countOfDistinctMonitorsFailed: List<Int>,
    val normalizedCountOfFailedMonitors: List<Double>,
)

object MutantKilling {

  private val monitorCombinations: List<Set<MonitorViolation>> =
      MonitorViolation.entries
          .toList()
          .allNonEmptySubsets()
          .sortedWith(
              compareBy<Set<MonitorViolation>> { it.size }
                  .thenBy { set -> set.map { it.name }.sorted().joinToString("_") })

  fun evaluate() {
    DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
    db {
      val failedMonitors = MetricFailedMonitorsTable
      val joinedWithTSCInstances =
          failedMonitors.join(
              otherTable = MetricStartingValidTSCInstancesTable,
              onColumn = MetricFailedMonitorsTable.startingScenarioConfiguration,
              otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
              joinType = JoinType.LEFT)

      val fullQuery =
          joinedWithTSCInstances.select(
              MetricFailedMonitorsTable.mutant,
              MetricStartingValidTSCInstancesTable.tscInstance,
              MetricFailedMonitorsTable.startingScenarioConfiguration,
              MetricFailedMonitorsTable.monitorG0Failed,
              MetricFailedMonitorsTable.monitorG1Failed,
              MetricFailedMonitorsTable.monitorG2Failed,
              MetricFailedMonitorsTable.monitorG3Failed,
              MetricFailedMonitorsTable.monitorG4Failed,
              MetricFailedMonitorsTable.monitorG5Failed,
              MetricFailedMonitorsTable.monitorI1Failed,
              MetricFailedMonitorsTable.monitorI2Failed,
              MetricFailedMonitorsTable.monitorI3Failed)

      println("Starting to load data from DB")
      val result = buildFailedMonitorMapping(fullQuery)
      println("Finished loading data from DB: ${result.size}")

      monitorCombinations.forEach { monitorCombination ->
        println("Evaluating monitor combination: ${monitorCombination.toFileNameSuffix()}")
        createBoxPlot(
            scenarioFailures = result, repetitions = 1_000, selectedMonitors = monitorCombination)
      }
    }
  }

  private fun createBoxPlot(
      scenarioFailures: List<ScenarioFailure>,
      repetitions: Int,
      selectedMonitors: Set<MonitorViolation>,
      scenarioInstancesPerRepetition: Int = 1_000
  ) {
    val allScenarios = scenarioFailures.map { it.scenarioId }

    val coverageList = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 80, 100, 120, 140, 160)

    //    val coverageList = List(allScenarios.size) { it + 1 }

    val consoleProgress =
        ConsoleProgress(
            total = coverageList.size,
            label = "Evaluating mutant killing (${selectedMonitors.toFileNameSuffix()}): ")

    val boxPlotData = mutableMapOf<Int, BoxPlotData>()

    coverageList.forEach { coverage ->
      consoleProgress.step()

      val countOfKilledMutants = mutableListOf<Int>()
      val normalizedCountOfKilledMutants = mutableListOf<Double>()
      val countOfFailedMonitors = mutableListOf<Int>()
      val normalizedCountOfFailedMonitors = mutableListOf<Double>()
      val countOfDistinctMonitorsFailed = mutableListOf<Int>()

      repeat(repetitions) {
        val repetitionScenarioIds = allScenarios.drawRandomElements(coverage)
        val filteredScenarioFailures =
            scenarioFailures.filter { it.scenarioId in repetitionScenarioIds }

        val minAmountOfScenarioInstancesInRepetition =
            filteredScenarioFailures.minOf { it.scenarios.size }

        val scenarioInstancesToDrawPerScenario =
            min(scenarioInstancesPerRepetition / coverage, minAmountOfScenarioInstancesInRepetition)

        val drawnScenarioInstances =
            filteredScenarioFailures.flatMap {
              it.scenarios.drawRandomElements(scenarioInstancesToDrawPerScenario)
            }

        val relevantMonitors =
            drawnScenarioInstances.flatMap { scenarioInstance ->
              scenarioInstance.mutants.filter { mutant ->
                mutant.violations.any { it in selectedMonitors }
              }
            }

        val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()
        countOfKilledMutants += mutantsKilled
        normalizedCountOfKilledMutants +=
            mutantsKilled / (scenarioInstancesToDrawPerScenario.toDouble() * coverage)

        val monitorsFailed = relevantMonitors.flatMap { it.violations }.count()
        countOfFailedMonitors += monitorsFailed
        normalizedCountOfFailedMonitors +=
            monitorsFailed / (scenarioInstancesToDrawPerScenario.toDouble() * coverage)

        val distinctMonitorsFailed = relevantMonitors.flatMap { it.violations }.toSet().count()
        countOfDistinctMonitorsFailed += distinctMonitorsFailed
      }

      boxPlotData +=
          coverage to
              BoxPlotData(
                  countOfKilledMutants = countOfKilledMutants,
                  normalizedCountOfKilledMutants = normalizedCountOfKilledMutants,
                  countOfFailedMonitors = countOfFailedMonitors,
                  countOfDistinctMonitorsFailed = countOfDistinctMonitorsFailed,
                  normalizedCountOfFailedMonitors = normalizedCountOfFailedMonitors)
    }

    writeCSVFiles(map = boxPlotData, selectedMonitors = selectedMonitors)
  }

  data class BoxPlotValues(
      val median: Double,
      val firstQuartile: Double,
      val thirdQuartile: Double,
      val min: Double,
      val max: Double,
      val allData: List<Double>
  )

  fun getBoxPlotValues(boxPlotData: List<Number>): BoxPlotValues {
    val sortedDoubleList = boxPlotData.map { it.toDouble() }.sorted()
    return BoxPlotValues(
        median = median(sortedDoubleList),
        firstQuartile = sortedDoubleList.nTile(0.25),
        thirdQuartile = sortedDoubleList.nTile(0.75),
        min = sortedDoubleList.min(),
        max = sortedDoubleList.max(),
        allData = sortedDoubleList)
  }

  fun List<Double>.nTile(n: Double): Double {
    val sortedList = this.sorted()
    val position = sortedList.size * n
    return if (position % 1 != 0.0) {
      (sortedList[floor(position).toInt()] + sortedList[ceil(position).toInt()]) / 2
    } else {
      sortedList[position.toInt()]
    }
  }

  fun writeCSVAndTeXFile(
      fileName: String,
      selectedMonitors: Set<MonitorViolation>,
      map: Map<Int, BoxPlotValues>
  ) {
    val csvString =
        map.map { (coverage, boxPlotValues) ->
              "${coverage}, ${boxPlotValues.median}, ${boxPlotValues.firstQuartile}, ${boxPlotValues.thirdQuartile}, ${boxPlotValues.min}, ${boxPlotValues.max}"
            }
            .joinToString(
                separator = "\n",
                prefix = "coverage, median, firstQuartile, thirdQuartile, min, max\n")
    val csvPath =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "mutant_killings",
            fileName,
            selectedMonitors.toFileNameSuffix(),
            "$fileName.csv")
    Files.createDirectories(csvPath.parent)
    csvPath.writeText(csvString)

    val fullDataCsvString =
        map.map { (coverage, boxPlotValues) ->
              "${coverage}, ${boxPlotValues.allData.joinToString(",")}"
            }
            .joinToString(separator = "\n", prefix = "coverage, dataPoints\n")

    val fullDataCsvPath =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "mutant_killings",
            fileName,
            selectedMonitors.toFileNameSuffix(),
            "${fileName}_full_data.csv")
    Files.createDirectories(csvPath.parent)
    fullDataCsvPath.writeText(fullDataCsvString)

    writeTeXFile(fileName, selectedMonitors)
  }

  fun writeCSVFiles(map: Map<Int, BoxPlotData>, selectedMonitors: Set<MonitorViolation>) {
    writeCSVAndTeXFile(
        "normalizedCountOfKilledMutants",
        selectedMonitors,
        map.map { it.key to getBoxPlotValues(it.value.normalizedCountOfKilledMutants) }.toMap(),
    )
    writeCSVAndTeXFile(
        "countOfKilledMutants",
        selectedMonitors,
        map.map { it.key to getBoxPlotValues(it.value.countOfKilledMutants) }.toMap())
    writeCSVAndTeXFile(
        "countOfFailedMonitors",
        selectedMonitors,
        map.map { it.key to getBoxPlotValues(it.value.countOfFailedMonitors) }.toMap())
    writeCSVAndTeXFile(
        "countOfDistinctMonitorsFailed",
        selectedMonitors,
        map.map { it.key to getBoxPlotValues(it.value.countOfDistinctMonitorsFailed) }.toMap())
    writeCSVAndTeXFile(
        "normalizedCountOfFailedMonitors",
        selectedMonitors,
        map.map { it.key to getBoxPlotValues(it.value.normalizedCountOfFailedMonitors) }.toMap())
  }

  fun <T> Collection<T>.drawRandomElements(x: Int): Collection<T> {
    require(x >= 0) { "x must be non-negative" }
    require(x <= size) { "x must not be larger than list size" }
    return shuffled(RAND).take(x)
  }

  fun writeTeXFile(fileName: String, selectedMonitors: Set<MonitorViolation>) {
    val teXCode =
        """
\documentclass[tikz,border=5pt]{standalone}

\usepackage{pgfplotstable}
\pgfplotsset{compat=1.8}
\usepgfplotslibrary{statistics}
\makeatletter
\pgfplotsset{
    boxplot prepared from table/.code={
        \def\tikz@plot@handler{\pgfplotsplothandlerboxplotprepared}%
        \pgfplotsset{
            /pgfplots/boxplot prepared from table/.cd,
            #1,
        }
    },
    /pgfplots/boxplot prepared from table/.cd,
    table/.code={\pgfplotstablecopy{#1}\to\boxplot@datatable},
    row/.initial=0,
    make style readable from table/.style={
        #1/.code={
            \pgfplotstablegetelem{\pgfkeysvalueof{/pgfplots/boxplot prepared from table/row}}{##1}\of\boxplot@datatable
            \pgfplotsset{boxplot/#1/.expand once={\pgfplotsretval}}
        }
    },
    make style readable from table=lower whisker,
    make style readable from table=upper whisker,
    make style readable from table=lower quartile,
    make style readable from table=upper quartile,
    make style readable from table=median,
    make style readable from table=lower notch,
    make style readable from table=upper notch
}
\makeatother

\newcommand{\boxplotcsv}{${fileName}.csv}

\begin{document}
    \begin{tikzpicture}
        \pgfplotstableread[col sep=comma]{\boxplotcsv}\datatable
        \pgfplotstablegetrowsof{\datatable}
        \pgfmathtruncatemacro{\NumRows}{\pgfplotsretval}
        \pgfmathtruncatemacro{\TotalRows}{\NumRows-1}
        
        \begin{axis}[
            title={${selectedMonitors.toFileNameSuffix().replace("_", " + ")}},
            boxplot/draw direction=y,
            xlabel={\# TSC classes covered},
            ylabel={\# mutants killed},
            xtick={1,...,\NumRows},
            xticklabels from table={\datatable}{coverage},
            ]
            \pgfplotsinvokeforeach{0,...,\TotalRows}{
                \addplot[
                boxplot prepared from table={
                    table=\datatable,
                    row=#1,
                    lower whisker=min,
                    lower quartile=firstQuartile,
                    median=median,
                    upper quartile=thirdQuartile,
                    upper whisker=max,
                },
                boxplot prepared,
                ]
                coordinates {};
            }
        \end{axis}
    \end{tikzpicture}
\end{document}     
        """

    val texPath =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "mutant_killings",
            fileName,
            selectedMonitors.toFileNameSuffix(),
            "$fileName.tex")
    Files.createDirectories(texPath.parent)
    texPath.writeText(teXCode)
  }

  private fun ResultRow.toMonitorViolations(): List<MonitorViolation> {
    val violations = mutableListOf<MonitorViolation>()

    if (this[MetricFailedMonitorsTable.monitorG0Failed]) violations += MonitorViolation.G0Accidents
    if (this[MetricFailedMonitorsTable.monitorG1Failed])
        violations += MonitorViolation.G1SafeDistance
    if (this[MetricFailedMonitorsTable.monitorG2Failed])
        violations += MonitorViolation.G2UnnecessaryBraking
    if (this[MetricFailedMonitorsTable.monitorG3Failed])
        violations += MonitorViolation.G3MaximumSpeedLimit
    if (this[MetricFailedMonitorsTable.monitorG4Failed])
        violations += MonitorViolation.G4TrafficFlow
    if (this[MetricFailedMonitorsTable.monitorG5Failed])
        violations += MonitorViolation.G5EmergencyBraking
    if (this[MetricFailedMonitorsTable.monitorI1Failed]) violations += MonitorViolation.I1Stopping
    if (this[MetricFailedMonitorsTable.monitorI2Failed])
        violations += MonitorViolation.I2FasterThanLeftTraffic
    if (this[MetricFailedMonitorsTable.monitorI3Failed])
        violations += MonitorViolation.I3DangerousCutIn

    return violations
  }

  private fun buildFailedMonitorMapping(query: Query): List<ScenarioFailure> {
    val result =
        mutableMapOf<ScenarioId, MutableMap<ScenarioInstanceId, MutableList<MutantFailures>>>()

    for (row in query) {
      val tscInstanceId = row[MetricStartingValidTSCInstancesTable.tscInstance].value
      val scenarioInstanceId = row[MetricFailedMonitorsTable.startingScenarioConfiguration].value
      val mutantId = row[MetricFailedMonitorsTable.mutant].value
      val violations = row.toMonitorViolations()

      val scenarios = result.getOrPut(tscInstanceId) { mutableMapOf() }
      val mutants = scenarios.getOrPut(scenarioInstanceId) { mutableListOf() }

      mutants += MutantFailures(mutantId = mutantId, violations = violations)
    }

    return result.map { (tscInstanceId, scenarios) ->
      ScenarioFailure(
          scenarioId = tscInstanceId,
          scenarios =
              scenarios.map { (scenarioInstanceId, mutants) ->
                ScenarioInstanceFailures(scenarioInstanceId = scenarioInstanceId, mutants = mutants)
              })
    }
  }

  private fun Set<MonitorViolation>.toFileNameSuffix(): String =
      this.sortedBy { it.name }.joinToString(separator = "_") { it.name }.ifBlank { "none" }

  private fun <T> List<T>.allNonEmptySubsets(): List<Set<T>> {
    val result = mutableListOf<Set<T>>()
    val n = size

    for (mask in 1 until (1 shl n)) {
      val subset = buildSet {
        for (i in 0 until n) {
          if ((mask and (1 shl i)) != 0) {
            add(this@allNonEmptySubsets[i])
          }
        }
      }
      result += subset
    }

    return result
  }
}
