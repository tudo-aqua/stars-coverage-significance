#!/usr/bin/env python3
"""Scatter plots comparing three next-tick test-suite sampling strategies.

Reads baseline_next_tick_{random,tsc,leaf}.csv, each containing one
mutant-killed count per repetition (single column, one value per line).
Produces one scatter plot file per strategy.
"""
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 75
AXES_LINEWIDTH = 2.5

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


def plot_strategy(values: np.ndarray, label: str, out_stem: Path, y_max: float) -> None:
    rng = np.random.default_rng(42)
    fig, ax = plt.subplots(figsize=(24, 24))

    jitter = rng.uniform(-0.22, 0.22, size=len(values))
    ax.scatter(
        np.zeros(len(values)) + jitter,
        values,
        s=100,
        alpha=0.35,
        linewidths=0,
        c="black",
    )

    ax.set_xticks([0])
    ax.set_xticklabels([label])
    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)
    ax.set_xlim(-0.5, 0.5)
    ax.set_ylim(-1, y_max + 1)

    fig.tight_layout()
    for suffix in (".png", ".pdf"):
        fig.savefig(out_stem.with_suffix(suffix), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved scatter plot to {out_stem.with_suffix('.pdf')}")


def main() -> None:
    base = Path(__file__).resolve().parent

    all_data = {key: read_results(base / f"baseline_next_tick_{key}.csv") for key, _ in STRATEGIES}
    y_max = float(max(v.max() for v in all_data.values()))

    for key, label in STRATEGIES:
        plot_strategy(all_data[key], label, base / f"baseline_next_tick_scatter_{key}", y_max)


if __name__ == "__main__":
    main()
