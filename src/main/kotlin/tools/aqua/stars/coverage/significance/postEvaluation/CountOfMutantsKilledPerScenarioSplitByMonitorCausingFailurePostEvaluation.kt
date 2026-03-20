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

object CountOfMutantsKilledPerScenarioSplitByMonitorCausingFailurePostEvaluation {

  val FEATURES = smallStaticTsc().getSetOfAllFeatureNames()

  const val CORRIDOR_DIVIDER = 70

  fun evaluate(
      allTSCInstances: List<ScenarioIdAndJSON>,
      randomTrafficTSCInstances: List<HighwayTrafficScenarioInstanceId>,
      filteredMutantFailures: List<MutantFailure>
  ) {
    println("Starting CountOfMutantsKilledPerScenarioPostEvaluationSplitByMonitorCausingFailure.")
    val longtail =
        allTSCInstances
            .map { it to randomTrafficTSCInstances.count { t -> t == it.scenarioId } }
            .sortedByDescending { it.second }

    val values: List<Triple<ScenarioIdAndJSON, Int, Map<String, Int>>> =
        longtail.map { scenarioAndLongtailCount ->
          val mutantFailuresInScenario =
              filteredMutantFailures.filter {
                it.tscInstance == scenarioAndLongtailCount.first.scenarioId
              }
          Triple(
              scenarioAndLongtailCount.first,
              scenarioAndLongtailCount.second,
              mapOf(
                  "G0Accidents" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 1) == 1 }
                          .map { it.mutantID }
                          .toSet()
                          .size,
                  "G1SafeDistance" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 2) == 2 }
                          .map { it.mutantID }
                          .toSet()
                          .size,
                  "G4TrafficFlow" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 16) == 16 }
                          .map { it.mutantID }
                          .toSet()
                          .size,
                  "I2FasterThanLeftTraffic" to
                      mutantFailuresInScenario
                          .filter { (it.monitorBitmask and 128) == 128 }
                          .map { it.mutantID }
                          .toSet()
                          .size))
        }

    val csvFileName = "countOfMutantsKilledPerScenarioSplitByMonitorCausingFailure.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_mutants_killed_per_scenario_split_by_monitor_causing_failure",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        values.joinToString(
            prefix =
                "Scenario, Frequency in longtail, Count of mutants killed by G0Accidents, Count of mutants killed by G1SafeDistance, Count of mutants killed by G4TrafficFlow, Count of mutants killed by I2FasterThanLeftTraffic\n",
            separator = "\n") {
              "${it.first.scenarioId},${it.second},${it.third["G0Accidents"]},${it.third["G1SafeDistance"]},${it.third["G4TrafficFlow"]},${it.third["I2FasterThanLeftTraffic"]}"
            })
    writePythonPlotFile(path.parent, csvFileName)
    println("Finished CountOfMutantsKilledPerScenarioPostEvaluation.")
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
            dpi: int = 300,
        ) -> None:
            x = range(len(df))

            fig, ax_left = plt.subplots(figsize=(14, 6))
            ax_right = ax_left.twinx()

            ax_left.bar(x, df["Frequency in longtail"], alpha=0.6)
            ax_left.set_xlabel("Scenario index (sorted by long-tail frequency)")
            ax_left.set_ylabel("Frequency in longtail")
            ax_left.set_title("Long-tail frequency and count of mutants killed per scenario")

            ax_right.scatter(
                x=df["Scenario"],
                y=df["Count of mutants killed by G0Accidents"],
                color="red",
                label="G0Accidents",
            )
            ax_right.scatter(
                x=df["Scenario"],
                y=df["Count of mutants killed by G1SafeDistance"],
                color="red",
                label="G1SafeDistance",
            )
            ax_right.scatter(
                x=df["Scenario"],
                y=df["Count of mutants killed by G4TrafficFlow"],
                color="red",
                label="G4TrafficFlow",
            )
            ax_right.scatter(
                x=df["Scenario"],
                y=df["Count of mutants killed by I2FasterThanLeftTraffic"],
                color="red",
                label="I2FasterThanLeftTraffic",
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

            plot_combined(df, args.output, dpi=args.dpi)


        if __name__ == "__main__":
            main()
        """
            .trimIndent()

    pythonFile.writeText(pythonScript)
  }
}
