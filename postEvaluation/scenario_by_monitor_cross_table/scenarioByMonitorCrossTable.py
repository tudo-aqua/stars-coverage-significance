#!/usr/bin/env python3
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm


def main(csv_path: Path) -> None:
    output_path = csv_path.parent / f"{csv_path.name[0:-3]}.png"

    df = pd.read_csv(csv_path, index_col=0)
    df.index = df.index.str.strip()
    df.columns = df.columns.str.strip()

    fig, ax = plt.subplots(figsize=(12, 10))
    cmap = ListedColormap(["green", "red"])
    norm = BoundaryNorm([-0.5, 0.5, 1.5], cmap.N)
    heatmap = ax.imshow(df.values, cmap=cmap, norm=norm, aspect="auto")
    ax.set_title("Scenario-by-Scenario Heatmap")
    ax.set_xlabel("Scenario")
    ax.set_ylabel("Scenario")
    fig.colorbar(heatmap, ax=ax, label="Value", ticks=[0, 1])

    # Too many UUID labels to render legibly; keep axes clean.
    ax.set_xticks([])
    ax.set_yticks([])

    fig.tight_layout()
    fig.savefig(output_path)
    plt.close(fig)


if __name__ == '__main__':
    for monitor in Path(__file__).resolve().parent.iterdir():
        if monitor.suffix == ".csv":
            main(monitor)
