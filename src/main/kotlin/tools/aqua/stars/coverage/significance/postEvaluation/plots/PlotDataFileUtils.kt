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

package tools.aqua.stars.coverage.significance.postEvaluation.plots

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.math.floor
import org.jetbrains.letsPlot.core.plot.base.stat.AggregateFunctions.median
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.BoxPlotValues
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.PlotData

private fun writeCSVFiles(
    metricName: String,
    fileName: String,
    selectedMonitors: Set<MonitorViolation>,
    coverageToBoxPlotValuesMap: Map<Int, BoxPlotValues>,
) {
  val fullDataCsvString = coverageToBoxPlotValuesMap.getFullDataCSVString()
  val fullDataCsvPath =
      Path.of(
          POST_EVALUATION_BASE_DIR,
          metricName,
          fileName,
          selectedMonitors.toFileNameSuffix(),
          "${fileName}_full_data.csv")
  Files.createDirectories(fullDataCsvPath.parent)
  fullDataCsvPath.writeText(fullDataCsvString)

  //  val boxPlotCsvString = coverageToBoxPlotValuesMap.getBoxPlotCSVString()
  //  val boxPlotCsvPath =
  //      Path.of(
  //          POST_EVALUATION_BASE_DIR,
  //          metricName,
  //          fileName,
  //          selectedMonitors.toFileNameSuffix(),
  //          "$fileName.csv")
  //  Files.createDirectories(boxPlotCsvPath.parent)
  //  boxPlotCsvPath.writeText(boxPlotCsvString)

  //  val middleOutliersCsvString = coverageToBoxPlotValuesMap.getMildOutliersCSVString()
  //  val middleOutliersCsvPath =
  //      Path.of(
  //          POST_EVALUATION_BASE_DIR,
  //          metricName,
  //          fileName,
  //          selectedMonitors.toFileNameSuffix(),
  //          "${fileName}_mild_outliers.csv")
  //  Files.createDirectories(middleOutliersCsvPath.parent)
  //  middleOutliersCsvPath.writeText(middleOutliersCsvString)

  //  val extremeOutliersCsvString = coverageToBoxPlotValuesMap.getExtremeOutliersCSVString()
  //  val extremeOutliersCsvPath =
  //      Path.of(
  //          POST_EVALUATION_BASE_DIR,
  //          metricName,
  //          fileName,
  //          selectedMonitors.toFileNameSuffix(),
  //          "${fileName}_extreme_outliers.csv")
  //  Files.createDirectories(extremeOutliersCsvPath.parent)
  //  extremeOutliersCsvPath.writeText(extremeOutliersCsvString)
}

fun writeCSVAndTeXFiles(
    metricName: String,
    map: Map<Int, PlotData>,
    selectedMonitors: Set<MonitorViolation>,
    numberOfMutants: Int
) {
  writeCSVFiles(
      metricName = metricName,
      fileName = "countOfKilledMutants",
      selectedMonitors,
      map.map { it.key to it.value.countOfKilledMutants.getBoxPlotValues() }.toMap())
  //  writeTeXFile(metricName = metricName, "countOfKilledMutants", selectedMonitors,
  // numberOfMutants)
  //  writeCSVFiles(
  //      metricName = metricName,
  //      "countOfFailedMonitors",
  //      selectedMonitors,
  //      map.map { it.key to it.value.countOfFailedMonitors.getBoxPlotValues() }.toMap())
  //  writeTeXFile(metricName = metricName, "countOfFailedMonitors", selectedMonitors,
  // numberOfMutants)
  //  writeCSVFiles(
  //      metricName = metricName,
  //      "countOfDistinctMonitorsFailed",
  //      selectedMonitors,
  //      map.map { it.key to it.value.countOfDistinctMonitorsFailed.getBoxPlotValues() }.toMap())
  //  writeTeXFile(
  //      metricName = metricName, "countOfDistinctMonitorsFailed", selectedMonitors,
  // numberOfMutants)
}

// private fun writeTeXFile(
//    metricName: String,
//    fileName: String,
//    selectedMonitors: Set<MonitorViolation>,
//    numberOfMutants: Int
// ) {
//  val teXCode =
//      """
// \documentclass[tikz,border=5pt]{standalone}
//
// \usepackage{pgfplotstable}
// \pgfplotsset{compat=1.8}
// \usepgfplotslibrary{statistics}
// \makeatletter
// \pgfplotsset{
//    boxplot prepared from table/.code={
//        \def\tikz@plot@handler{\pgfplotsplothandlerboxplotprepared}%
//        \pgfplotsset{
//            /pgfplots/boxplot prepared from table/.cd,
//            #1,
//        }
//    },
//    /pgfplots/boxplot prepared from table/.cd,
//    table/.code={\pgfplotstablecopy{#1}\to\boxplot@datatable},
//    row/.initial=0,
//    make style readable from table/.style={
//        #1/.code={
//            \pgfplotstablegetelem{\pgfkeysvalueof{/pgfplots/boxplot prepared from
// table/row}}{##1}\of\boxplot@datatable
//            \pgfplotsset{boxplot/#1/.expand once={\pgfplotsretval}}
//        }
//    },
//    make style readable from table=lower whisker,
//    make style readable from table=upper whisker,
//    make style readable from table=lower quartile,
//    make style readable from table=upper quartile,
//    make style readable from table=median,
//    make style readable from table=lower notch,
//    make style readable from table=upper notch
// }
// \makeatother
//
// \newcommand{\boxplotcsv}{${fileName}.csv}
// \newcommand{\boxplotMildOutlierscsv}{${fileName}_mild_outliers.csv}
// \newcommand{\boxplotExtremeOutlierscsv}{${fileName}_extreme_outliers.csv}
//
// \begin{document}
//    \begin{tikzpicture}
//        \pgfplotstableread[col sep=comma]{\boxplotcsv}\datatable
//        \pgfplotstablegetrowsof{\datatable}
//
//        \pgfplotstableread[col sep=comma]{\boxplotMildOutlierscsv}\datatableMildOutliers
//        \pgfplotstableread[col sep=comma]{\boxplotExtremeOutlierscsv}\datatableExtremeOutliers
//
//        \pgfmathtruncatemacro{\NumRows}{\pgfplotsretval}
//        \pgfmathtruncatemacro{\TotalRows}{\NumRows-1}
//
//        \begin{axis}[
//            title={${selectedMonitors.toFileNameSuffix().replace("_", " + ")}},
//            width=30cm,
//            boxplot/draw direction=y,
//            xlabel={\# TSC classes covered},
//            ylabel={\# mutants killed},
//            xtick={0,10,20,...,\NumRows},
//            xmin=-1,
//            xmax=\NumRows+2,
//            ymin=0,
//            ymax=${numberOfMutants}+2
//            ]
//            \pgfplotsinvokeforeach{0,...,\TotalRows}{
//                \addplot[
//                boxplot prepared from table={
//                    table=\datatable,
//                    row=#1,
//                    lower whisker=min,
//                    lower quartile=firstQuartile,
//                    median=median,
//                    upper quartile=thirdQuartile,
//                    upper whisker=max,
//                },
//                boxplot prepared,
//                ]
//                coordinates {};
//                \addplot[only marks, mark size=.2ex, mark=o, color=blue] table
// {\datatableMildOutliers};
//                \addplot[only marks, mark size=.2ex, mark=asterisk, color=red] table
// {\datatableExtremeOutliers};
//            }
//        \end{axis}
//    \end{tikzpicture}
// \end{document}
//        """
//
//  val texPath =
//      Path.of(
//          POST_EVALUATION_BASE_DIR,
//          metricName,
//          fileName,
//          selectedMonitors.toFileNameSuffix(),
//          "$fileName.tex")
//  Files.createDirectories(texPath.parent)
//  texPath.writeText(teXCode)
// }

private fun List<Number>.getBoxPlotValues(): BoxPlotValues {
  val sortedDoubleList = this.map { it.toDouble() }.sorted()
  val firstQuartile = sortedDoubleList.nTile(0.25)
  val thirdQuartile = sortedDoubleList.nTile(0.75)

  val iqr = thirdQuartile - firstQuartile
  val lowerWhisker = maxOf(sortedDoubleList.min(), firstQuartile - 1.5 * iqr)
  val upperWhisker = minOf(sortedDoubleList.max(), thirdQuartile + 1.5 * iqr)
  val lowerMildOutlierBound = lowerWhisker - (1.5 * iqr)
  val upperMildOutlierBound = upperWhisker + (1.5 * iqr)

  val mildOutliers =
      sortedDoubleList
          .filter {
            it in lowerMildOutlierBound..lowerWhisker || it in upperWhisker..upperMildOutlierBound
          }
          .distinct()
  val extremeOutliers =
      sortedDoubleList.filter { it !in lowerMildOutlierBound..upperMildOutlierBound }.distinct()

  return BoxPlotValues(
      median = median(sortedDoubleList),
      firstQuartile = firstQuartile,
      thirdQuartile = thirdQuartile,
      lowerWhisker = lowerWhisker,
      upperWhisker = upperWhisker,
      mildOutliers = mildOutliers,
      extremeOutliers = extremeOutliers,
      allData = sortedDoubleList)
}

private fun Map<Int, BoxPlotValues>.getFullDataCSVString() =
    this.map { (coverage, boxPlotValues) ->
          "${coverage}, ${boxPlotValues.allData.joinToString(",")}"
        }
        .joinToString(separator = "\n", prefix = "coverage, dataPoints\n")

// private fun Map<Int, BoxPlotValues>.getBoxPlotCSVString() =
//    this.map { (coverage, boxPlotValues) ->
//          "${coverage}, ${boxPlotValues.median}, ${boxPlotValues.firstQuartile},
// ${boxPlotValues.thirdQuartile}, ${boxPlotValues.lowerWhisker}, ${boxPlotValues.upperWhisker}"
//        }
//        .joinToString(
//            separator = "\n", prefix = "coverage, median, firstQuartile, thirdQuartile, min,
// max\n")

// private fun Map<Int, BoxPlotValues>.getMildOutliersCSVString() =
//    this.flatMap { (coverage, boxPlotValues) ->
//          boxPlotValues.mildOutliers.map { "${coverage}, $it" }
//        }
//        .joinToString(separator = "\n", prefix = "coverage, outlier\n")

// private fun Map<Int, BoxPlotValues>.getExtremeOutliersCSVString() =
//    this.flatMap { (coverage, boxPlotValues) ->
//          boxPlotValues.extremeOutliers.map { "${coverage}, $it" }
//        }
//        .joinToString(separator = "\n", prefix = "coverage, outlier\n")

private fun List<Double>.nTile(n: Double): Double {
  val sortedList = this.sorted()
  val position = sortedList.size * n
  return if (position % 1 != 0.0) {
    (sortedList[floor(position).toInt()] + sortedList[ceil(position).toInt()]) / 2
  } else {
    sortedList[position.toInt()]
  }
}

fun Set<MonitorViolation>.toFileNameSuffix(): String =
  this.sortedBy { it.name }.joinToString(separator = "_") { it.name }.ifBlank { "none" }

