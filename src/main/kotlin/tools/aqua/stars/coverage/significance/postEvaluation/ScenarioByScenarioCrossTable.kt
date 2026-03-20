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
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress

object ScenarioByScenarioCrossTable {

  fun evaluate(filteredMutantFailures: List<MutantFailure>, scenarioIds: List<UUID>) {
    val consoleProgress = ConsoleProgress(total = scenarioIds.size * scenarioIds.size)
    println("ScenarioByScenarioCrossTable - Starting")
    val heatmap =
        Array(scenarioIds.size) { Array<Triple<UUID, UUID, Int>?>(scenarioIds.size) { null } }

    scenarioIds.forEachIndexed { outerIndex, outerScenarioId ->
      val distinctMutantsKilledByOuterScenario =
          filteredMutantFailures
              .filter { it.tscInstance == outerScenarioId }
              .map { it.mutantID }
              .toSet()

      scenarioIds.forEachIndexed { innerIndex, innerScenarioId ->
        consoleProgress.step("Running scenario $outerIndex in $innerIndex")
        val distinctMutantsKilledByInnerScenario =
            filteredMutantFailures
                .filter { it.tscInstance == innerScenarioId }
                .map { it.mutantID }
                .toSet()

        val difference = distinctMutantsKilledByOuterScenario - distinctMutantsKilledByInnerScenario
        heatmap[outerIndex][innerIndex] = Triple(outerScenarioId, innerScenarioId, difference.size)
      }
    }

    val rowSortedHeatmap = heatmap.sortedBy { row -> row.sumOf { it!!.third } }

    val columnOrder =
        scenarioIds.indices.sortedBy { columnIndex ->
          rowSortedHeatmap.sumOf { row -> row[columnIndex]!!.third }
        }

    val fullySortedHeatmap =
        rowSortedHeatmap
            .map { row -> columnOrder.map { columnIndex -> row[columnIndex]!! }.toTypedArray() }
            .toTypedArray()

    writeCSVFile(fullySortedHeatmap)
  }

  private fun writeCSVFile(heatmap: Array<Array<Triple<UUID, UUID, Int>>>) {
    val csvFileName = "scenario_by_scenario_cross_table.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "scenario_by_scenario_cross_table",
            csvFileName,
        )
    Files.createDirectories(path.parent)

    val sortedColumnScenarioIds = heatmap.first().map { it.second }
    val csvString =
        heatmap.joinToString(
            prefix =
                "x, ${sortedColumnScenarioIds.joinToString(separator = ",") { it.toString() }}\n",
            separator = "\n") { row ->
              row.joinToString(prefix = "${row.first().first},", separator = ",") {
                it.third.toString()
              }
            }

    path.writeText(csvString)
    writePythonHeatmapFile(path.parent, csvFileName)
  }

  private fun writePythonHeatmapFile(outputDirectory: Path, csvFileName: String) {
    val pythonFile = outputDirectory.resolve("scenario_by_scenario_cross_table.py")

    val pythonScript =
        """
        #!/usr/bin/env python3
        from __future__ import annotations

        import argparse
        from pathlib import Path

        import matplotlib.pyplot as plt
        import numpy as np
        import pandas as pd


        def load_heatmap(csv_path: Path) -> pd.DataFrame:
            df = pd.read_csv(csv_path, index_col=0, skipinitialspace=True)
            df = df.apply(pd.to_numeric, errors="raise")
            df.index = df.index.astype(str).str.strip()
            df.columns = df.columns.astype(str).str.strip()
            return df


        def sparse_tick_positions(n: int, target: int = 10) -> np.ndarray:
            if n <= target:
                return np.arange(n)
            step = max(1, n // target)
            ticks = np.arange(0, n, step)
            if ticks[-1] != n - 1:
                ticks = np.append(ticks, n - 1)
            return ticks


        def plot_heatmap(
            df: pd.DataFrame,
            output_path: Path,
            title: str = "Scenario-by-Scenario Cross Table Heatmap",
            cmap: str = "viridis",
            dpi: int = 600,
        ) -> None:
            data = df.to_numpy()
            n_rows, n_cols = data.shape

            cell_size_inch = 0.08
            fig_width = min(max(8.0, n_cols * cell_size_inch), 18.0)
            fig_height = min(max(8.0, n_rows * cell_size_inch), 18.0)

            fig, ax = plt.subplots(figsize=(fig_width, fig_height))
            image = ax.imshow(data, aspect="equal", interpolation="nearest", cmap=cmap)

            ax.set_title(title)
            ax.set_xlabel("Inner scenario (sorted by global column count)")
            ax.set_ylabel("Outer scenario (sorted by row difference sum)")

            xticks = sparse_tick_positions(n_cols, target=10)
            yticks = sparse_tick_positions(n_rows, target=10)

            ax.set_xticks(xticks)
            ax.set_yticks(yticks)
            ax.set_xticklabels([df.columns[i][:8] for i in xticks], rotation=45, ha="right")
            ax.set_yticklabels([df.index[i][:8] for i in yticks])

            cbar = fig.colorbar(image, ax=ax)
            cbar.set_label("Difference size")

            fig.tight_layout()
            fig.savefig(output_path, dpi=dpi, bbox_inches="tight")
            plt.close(fig)


        def parse_args() -> argparse.Namespace:
            parser = argparse.ArgumentParser(
                description="Load a pre-sorted scenario-by-scenario cross-table CSV and export a heatmap."
            )
            parser.add_argument(
                "--output",
                type=Path,
                default=Path("scenario_by_scenario_cross_table_heatmap.png"),
                help="Output image path (default: scenario_by_scenario_cross_table_heatmap.png)",
            )
            parser.add_argument(
                "--title",
                default="Scenario-by-Scenario Cross Table Heatmap",
                help="Plot title",
            )
            parser.add_argument(
                "--cmap",
                default="viridis",
                help="Matplotlib colormap name (default: viridis)",
            )
            parser.add_argument(
                "--dpi",
                type=int,
                default=600,
                help="Output DPI (default: 600)",
            )
            return parser.parse_args()


        def main() -> None:
            args = parse_args()
            df = load_heatmap("$csvFileName")
            plot_heatmap(
                df=df,
                output_path=args.output,
                title=args.title,
                cmap=args.cmap,
                dpi=args.dpi,
            )
            print(f"Saved heatmap to: {args.output}")


        if __name__ == "__main__":
            main()
        """
            .trimIndent()

    pythonFile.writeText(pythonScript)
  }
}
