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
import kotlin.collections.sortedByDescending
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.HighwayTrafficScenarioInstanceId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON
import tools.aqua.stars.coverage.significance.smallStaticTsc
import tools.aqua.stars.coverage.significance.utils.getSetOfAllFeatureNames

object CountOfMutantsKilledPerScenarioPostEvaluation {

  val FEATURES = smallStaticTsc().getSetOfAllFeatureNames()

  const val CORRIDOR_DIVIDER = 70

  fun evaluate(
      allTSCInstances: List<ScenarioIdAndJSON>,
      randomTrafficTSCInstances: List<HighwayTrafficScenarioInstanceId>,
      filteredMutantFailures: List<MutantFailure>
  ) {
    println("Starting CountOfMutantsKilledPerScenarioPostEvaluation.")
    val longtail =
        allTSCInstances
            .map { it to randomTrafficTSCInstances.count { t -> t == it.scenarioId } }
            .sortedByDescending { it.second }

    val values: List<Triple<ScenarioIdAndJSON, Int, Int>> =
        longtail.map { l ->
          Triple(
              l.first,
              l.second,
              filteredMutantFailures
                  .filter { it.tscInstance == l.first.scenarioId }
                  .map { it.mutantID }
                  .toSet()
                  .size)
        }

    values.sortedByDescending { it.third }.take(5).forEach { println(it.first) }

    var csvFileName = "countOfMutantsKilledPerScenario.csv"
    var path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_mutants_killed_per_scenario",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        values.joinToString(
            prefix = "Scenario, Frequency in longtail, Count of mutants killed\n",
            separator = "\n") {
              "${it.first.scenarioId},${it.second},${it.third}"
            })
    writePythonPlotFile(path.parent, csvFileName)
    println("Finished CountOfMutantsKilledPerScenarioPostEvaluation.")

    // --------------------------------------------------
    // Cluster the two corridors
    // --------------------------------------------------

    val corridors = FEATURES.associateWith { 0 to 0 }.toMutableMap()

    values.forEach { (scenarioAndJSON, _, killedMutants) ->
      FEATURES.forEach { feature ->
        if (scenarioAndJSON.hasFeature(feature)) {
          val value = corridors[feature]!!

          corridors[feature] =
              if (killedMutants > CORRIDOR_DIVIDER) value.first + 1 to value.second
              else value.first to value.second + 1
        }
      }
    }
    csvFileName = "countOfMutantsKilledPerScenarioCorridors.csv"
    path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_mutants_killed_per_scenario_corridors",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        corridors.toList().joinToString(
            prefix = "Feature, Count in upper corridor, Count in lower corridor\n",
            separator = "\n") {
              "${it.first},${it.second.first},${it.second.second}"
            })
  }

  private fun writePythonPlotFile(outputDirectory: Path, csvFileName: String) {
    val pythonFile = outputDirectory.resolve("countOfMutantsKilledPerScenario.py")

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
            return df


        def feature_column_name(df: pd.DataFrame) -> str:
            for column in df.columns:
                if column.startswith("Feature ") and column.endswith(" active"):
                    return column
            raise ValueError("Could not find feature flag column.")


        def to_bool(series: pd.Series) -> pd.Series:
            return series.astype(str).str.strip().str.lower().map(
                {"true": True, "false": False}
            )


        def plot_combined(
            df: pd.DataFrame,
            output_path: Path,
            feature_column: str,
            dpi: int = 300,
        ) -> None:
            feature_active = to_bool(df[feature_column]).fillna(False)
            x = range(len(df))

            fig, ax_left = plt.subplots(figsize=(14, 6))
            ax_right = ax_left.twinx()

            ax_left.bar(x, df["Frequency in longtail"], alpha=0.6)
            ax_left.set_xlabel("Scenario index (sorted by long-tail frequency)")
            ax_left.set_ylabel("Frequency in longtail")
            ax_left.set_title("Long-tail frequency and count of mutants killed per scenario")

            ax_right.scatter(
                df.loc[~feature_active].index,
                df.loc[~feature_active, "Count of mutants killed"],
                label="Feature inactive",
            )
            ax_right.scatter(
                df.loc[feature_active].index,
                df.loc[feature_active, "Count of mutants killed"],
                label="Feature active",
            )
            ax_right.set_ylabel("Count of mutants killed")
            ax_right.legend(loc="upper right")

            fig.tight_layout()
            fig.savefig(output_path, dpi=dpi, bbox_inches="tight")
            plt.close(fig)


        def parse_args() -> argparse.Namespace:
            parser = argparse.ArgumentParser(
                description="Create a single combined matplotlib plot for $csvFileName"
            )
            parser.add_argument(
                "--output",
                type=Path,
                default=Path("countOfMutantsKilledPerScenario.png"),
                help="Output path for the combined plot",
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
            feature_column = feature_column_name(df)

            plot_combined(df, args.output, feature_column, dpi=args.dpi)


        if __name__ == "__main__":
            main()
        """
            .trimIndent()

    pythonFile.writeText(pythonScript)
  }
}
