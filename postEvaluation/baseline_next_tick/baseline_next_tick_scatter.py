#!/usr/bin/env python3
"""Scatter plot comparing three next-tick test-suite sampling strategies.

Reads baseline_next_tick_{random,tsc,leaf}.csv, each containing one
mutant-killed count per repetition (single column, one value per line).
Plots all three side-by-side as a strip/scatter with horizontal jitter.
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


def main() -> None:
    base = Path(__file__).resolve().parent
    rng = np.random.default_rng(42)

    fig, ax = plt.subplots(figsize=(24, 24))

    all_values: list[float] = []
    for x, (key, _) in enumerate(STRATEGIES):
        csv_path = base / f"baseline_next_tick_{key}.csv"
        values = read_results(csv_path)
        all_values.extend(values.tolist())
        jitter = rng.uniform(-0.22, 0.22, size=len(values))
        ax.scatter(
            np.full(len(values), x, dtype=float) + jitter,
            values,
            s=10,
            alpha=0.35,
            linewidths=0,
            c="black",
        )

    labels = [label for _, label in STRATEGIES]
    ax.set_xticks(range(len(STRATEGIES)))
    ax.set_xticklabels(labels)
    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)
    ax.set_xlim(-0.5, len(STRATEGIES) - 0.5)
    ax.set_ylim(0, max(all_values) + 5)

    fig.tight_layout()
    for suffix in (".png", ".pdf"):
        out = base / f"baseline_next_tick_scatter{suffix}"
        fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    print(f"Saved scatter plot to {base / 'baseline_next_tick_scatter.pdf'}")


if __name__ == "__main__":
    main()
