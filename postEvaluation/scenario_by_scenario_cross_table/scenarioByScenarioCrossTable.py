#!/usr/bin/env python3
from pathlib import Path

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt


def main() -> None:
    base_dir = Path(__file__).parent
    csv_path = base_dir / "scenario_by_scenario_cross_table.csv"
    output_path = base_dir / "scenario_by_scenario_cross_table_heatmap.png"

    df = pd.read_csv(csv_path, index_col=0)
    df.index = df.index.str.strip()
    df.columns = df.columns.str.strip()

    fig, ax = plt.subplots(figsize=(12, 10))
    heatmap = ax.imshow(df.values, cmap="Greys", aspect="auto")
    ax.set_title("Scenario-by-Scenario Heatmap")
    ax.set_xlabel("Scenario")
    ax.set_ylabel("Scenario")
    fig.colorbar(heatmap, ax=ax, label="Value")

    # Too many UUID labels to render legibly; keep axes clean.
    ax.set_xticks([])
    ax.set_yticks([])

    fig.tight_layout()
    fig.savefig(output_path, dpi=200)
    plt.close(fig)


if __name__ == '__main__':
    main()
