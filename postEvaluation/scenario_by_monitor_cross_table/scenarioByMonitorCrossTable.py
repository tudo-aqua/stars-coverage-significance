#!/usr/bin/env python3
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm


def main(csv_path: Path) -> None:
    output_path = csv_path.with_suffix(".png")

    stem_parts = csv_path.stem.split("_")
    monitor_name = stem_parts[1] if len(stem_parts) > 1 else csv_path.stem

    df = pd.read_csv(csv_path, index_col=0)
    df.index = df.index.astype(str).str.strip()
    df.columns = df.columns.astype(str).str.strip()

    fig_width = max(12, len(df.columns) * 0.35)
    fig_height = max(10, len(df.index) * 0.12)

    fig, ax = plt.subplots(figsize=(fig_width, fig_height))

    cmap = ListedColormap(["green", "red"])
    norm = BoundaryNorm([-0.5, 0.5, 1.5], cmap.N)

    heatmap = ax.imshow(df.values, cmap=cmap, norm=norm, aspect="auto")

    ax.set_title(f"Scenario-by-Monitor Heatmap (Monitor: {monitor_name})")
    ax.set_xlabel("Mutant (killed/not-killed)")
    ax.set_ylabel("Scenario (sorted by long-tail)")

    cbar = fig.colorbar(heatmap, ax=ax, label="Value", ticks=[0, 1])
    cbar.ax.set_yticklabels(["not killed", "killed"])

    # X axis: show simple counting indices
    x_tick_positions = list(range(len(df.columns)))
    ax.set_xticks(x_tick_positions)
    ax.set_xticklabels(
        [str(i) for i in x_tick_positions],
        rotation=90,
        ha="right",
        rotation_mode="anchor",
        fontsize=8
    )

    # Y axis: show only every 10th index
    y_tick_positions = list(range(0, len(df.index), 10))
    ax.set_yticks(y_tick_positions)
    ax.set_yticklabels([str(i) for i in y_tick_positions], fontsize=8)

    fig.tight_layout()
    fig.savefig(output_path, dpi=300, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    for csv_file in Path(__file__).resolve().parent.iterdir():
        if csv_file.suffix == ".csv":
            main(csv_file)