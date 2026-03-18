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
import tools.aqua.stars.coverage.significance.HighwayTrafficScenarioInstanceId
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.ScenarioIdAndJSON
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.smallStaticTsc
import tools.aqua.stars.coverage.significance.utils.getSetOfAllFeatureNames

typealias FeatureLabel = String

object DistributionOfFeaturesInLongtailPostEvaluation {
  fun evaluate(
      allTSCInstances: List<ScenarioIdAndJSON>,
      randomTrafficTSCInstances: List<HighwayTrafficScenarioInstanceId>,
      filteredMutantFailures: List<MutantFailure>,
  ) {
    println("Starting DistributionOfFeaturesInLongtailPostEvaluation.")

    val featureLabels: Set<FeatureLabel> = smallStaticTsc().getSetOfAllFeatureNames()

    println("Found ${featureLabels.size} feature labels.")
    featureLabels.forEach { println(it) }
    println()

    val featureToLongtailCountMap: Map<FeatureLabel, MutableList<Int>> =
        featureLabels.associateWith { mutableListOf() }

    allTSCInstances.forEach { scenarioIdAndJson ->
      val mutantCount =
          filteredMutantFailures
              .filter { it.startingScenario == scenarioIdAndJson.scenarioId }
              .map { it.mutantID }
              .toSet()
              .size
      featureLabels.forEach { featureLabel ->
        if (scenarioIdAndJson.scenarioJson.contains("\"label\":\"$featureLabel\"")) {
          featureToLongtailCountMap[featureLabel]!!.add(mutantCount)
        }
      }
    }

    featureToLongtailCountMap.forEach { (feature, counts) ->
      println("$feature: ${counts.average()}")
    }

    val sortedFeatureDistribution =
        featureToLongtailCountMap.entries
            .sortedByDescending { (_, counts) -> counts.average() }
            .map { (feature, counts) -> feature to counts.average() }

    writeCSVFile(sortedFeatureDistribution)
  }

  private fun writeCSVFile(sortedFeatureDistribution: List<Pair<String, Double>>) {
    val csvFileName = "featureDistribution.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "feature_distribution_in_longtail_scenarios",
            csvFileName,
        )
    Files.createDirectories(path.parent)

    val csvString =
        sortedFeatureDistribution.joinToString(
            prefix = "feature,count\n",
            separator = "\n",
        ) { (feature, averageCount) ->
          "$feature,$averageCount"
        }

    path.writeText(csvString)
    writePythonPlotFile(path.parent, csvFileName)
  }

  private fun writePythonPlotFile(outputDirectory: Path, csvFileName: String) {
    val pythonFile = outputDirectory.resolve("featureDistribution_plot.py")

    val pythonScript =
        """
        #!/usr/bin/env python3
        from __future__ import annotations

        import argparse
        from pathlib import Path

        import matplotlib.pyplot as plt
        import pandas as pd


        CSV_PATH = Path(__file__).with_name("$csvFileName")


        def load_data() -> pd.DataFrame:
            df = pd.read_csv(CSV_PATH, skipinitialspace=True)
            df.columns = [column.strip() for column in df.columns]
            df["count"] = pd.to_numeric(df["count"], errors="raise")
            return df


        def validate_sorted_descending(df: pd.DataFrame) -> None:
            if not df["count"].is_monotonic_decreasing:
                raise ValueError(
                    "CSV is not sorted descending by average feature count. "
                    "Expected the feature with the highest average in the first row."
                )


        def plot_feature_distribution(
            df: pd.DataFrame,
            output_path: Path,
            dpi: int = 300,
        ) -> None:
            fig, ax = plt.subplots(figsize=(14, 6))
            x = range(len(df))

            ax.bar(x, df["count"])
            ax.set_title("Feature distribution in long-tail scenarios")
            ax.set_xlabel("Feature index (sorted by descending average)")
            ax.set_ylabel("Average long-tail count")

            tick_count = min(12, len(df))
            if tick_count > 0:
                if len(df) <= tick_count:
                    tick_positions = list(range(len(df)))
                else:
                    step = max(1, len(df) // tick_count)
                    tick_positions = list(range(0, len(df), step))
                    if tick_positions[-1] != len(df) - 1:
                        tick_positions.append(len(df) - 1)
                ax.set_xticks(tick_positions)
                ax.set_xticklabels(
                    [df.iloc[i]["feature"] for i in tick_positions],
                    rotation=45,
                    ha="right",
                )

            fig.tight_layout()
            fig.savefig(output_path, dpi=dpi, bbox_inches="tight")
            plt.close(fig)


        def parse_args() -> argparse.Namespace:
            parser = argparse.ArgumentParser(
                description="Create a matplotlib plot for the exported feature distribution CSV."
            )
            parser.add_argument(
                "--output",
                type=Path,
                default=Path("featureDistribution_plot.png"),
                help="Output path for the feature distribution plot",
            )
            parser.add_argument(
                "--dpi",
                type=int,
                default=300,
                help="Output DPI",
            )
            return parser.parse_args()


        def main() -> None:
            args = parse_args()
            df = load_data()
            validate_sorted_descending(df)
            plot_feature_distribution(df, args.output, dpi=args.dpi)

            print(f"Loaded CSV from: {CSV_PATH}")
            print(f"Saved feature distribution plot to: {args.output}")


        if __name__ == "__main__":
            main()
        """
            .trimIndent()

    pythonFile.writeText(pythonScript)
  }
}
