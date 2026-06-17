#!/usr/bin/env python3
"""Box plots comparing three next-tick test-suite sampling strategies.

Reads baseline_next_tick_{random,tsc,leaf}.csv, each containing one
mutant-killed count per repetition (single column, one value per line).
"""
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 75
AXES_LINEWIDTH = 2.5
BOX_LINEWIDTH = 2.5

STRATEGIES = [
    ("random", "Random"),
    ("tsc",    "TSC"),
    ("leaf",   "Leaf"),
]


def read_results(csv_path: Path) -> np.ndarray:
    values = []
    with csv_path.open() as f:
        next(f)  # skip header
        for line in f:
            line = line.strip()
            if line:
                values.append(int(line))
    return np.array(values, dtype=float)


def main() -> None:
    base = Path(__file__).resolve().parent

    data = []
    labels = []
    for key, label in STRATEGIES:
        data.append(read_results(base / f"baseline_next_tick_{key}.csv"))
        labels.append(label)

    fig, ax = plt.subplots(figsize=(24, 24))

    bp = ax.boxplot(
        data,
        labels=labels,
        patch_artist=True,
        widths=0.5,
        medianprops=dict(color="black", linewidth=BOX_LINEWIDTH),
        boxprops=dict(facecolor="white", linewidth=BOX_LINEWIDTH),
        whiskerprops=dict(linewidth=BOX_LINEWIDTH),
        capprops=dict(linewidth=BOX_LINEWIDTH),
        flierprops=dict(marker="o", markersize=6, alpha=0.4, markeredgewidth=0),
    )

    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)
    ax.set_ylim(0, max(v.max() for v in data) + 5)

    fig.tight_layout()
    for suffix in (".png", ".pdf"):
        out = base / f"baseline_next_tick_boxplot{suffix}"
        fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    print(f"Saved box plot to {base / 'baseline_next_tick_boxplot.pdf'}")


if __name__ == "__main__":
    main()
