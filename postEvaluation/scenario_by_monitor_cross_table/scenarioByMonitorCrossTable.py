#!/usr/bin/env python3
from pathlib import Path

import matplotlib
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm

TICK_FONTSIZE = 20
AXES_LINEWIDTH = 1.5

def main(csv_path: Path) -> None:
    stem_parts = csv_path.stem.split("_")
    monitor_name = stem_parts[1] if len(stem_parts) > 1 else csv_path.stem

    df = pd.read_csv(csv_path, index_col=0)
    df.index = df.index.astype(str).str.strip()
    df.columns = df.columns.astype(str).str.strip()

    first_true_pos = pd.Series(
        {
            col: next(
                (i for i, v in enumerate(df[col].astype(bool).to_numpy()) if v),
                float("inf")
            )
            for col in df.columns
        }
    )

    column_order = first_true_pos.sort_values(ascending=False, kind="stable").index
    df = df.loc[:, column_order]
    df = df.transpose()

    fig, ax = plt.subplots(figsize=(10, 6))

    cmap = ListedColormap(["white", "black"])
    norm = BoundaryNorm([-0.5, 0.5, 1.5], cmap.N)

    heatmap = ax.imshow(df.values, cmap=cmap, norm=norm, aspect="auto")

    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)
    ax.set_title(f"Scenario-by-Monitor Heatmap (Monitor: {monitor_name})")
    ax.set_ylabel("Mutant (killed/not-killed)")
    ax.set_xlabel("Scenario (sorted by long-tail)")

    cbar = fig.colorbar(heatmap, ax=ax, label="Value", ticks=[0, 1])
    cbar.ax.set_yticklabels(["not killed", "killed"])

    ax.set_xticks(np.arange(0, len(df.columns), 20))
    ax.set_yticks(np.arange(0, len(df.index), 1))
    ax.set_yticklabels(np.arange(0, len(df.index), 1)[::-1])

    fig.tight_layout()
    fig.savefig(csv_path.with_suffix(".png"), dpi=300, bbox_inches="tight")
    fig.savefig(csv_path.with_suffix(".pdf"), dpi=300, bbox_inches="tight")
    plt.close(fig)

    print(f"Saved plot to {csv_path}")


if __name__ == "__main__":
    for csv_file in Path(__file__).resolve().parent.iterdir():
        if csv_file.suffix == ".csv":
            main(csv_file)