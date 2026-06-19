#!/usr/bin/env python3
"""Combined scatter + box plot for three next-tick test-suite sampling strategies.

For each strategy (Random, TSC, Leaf) the scatter plot (individual repetition
values with jitter) is drawn directly to the left of the box plot so the full
distribution and its summary are visible side by side.
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

# Within each strategy group: scatter at 0.0, box at 0.7; groups spaced 2.5 apart.
GROUP_SPACING = 2.5
SCATTER_OFFSET = 0.0
BOX_OFFSET = 0.7


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

    data = []
    for key, _ in STRATEGIES:
        data.append(read_results(base / f"baseline_next_tick_{key}.csv"))

    fig, ax = plt.subplots(figsize=(36, 24))

    scatter_positions = []
    box_positions = []
    tick_positions = []
    tick_labels = []

    for i, ((_, label), values) in enumerate(zip(STRATEGIES, data)):
        sx = i * GROUP_SPACING + SCATTER_OFFSET
        bx = i * GROUP_SPACING + BOX_OFFSET

        scatter_positions.append(sx)
        box_positions.append(bx)
        tick_positions.append((sx + bx) / 2)
        tick_labels.append(label)

        jitter = rng.uniform(-0.22, 0.22, size=len(values))
        ax.scatter(
            np.full(len(values), sx) + jitter,
            values,
            s=100,
            alpha=0.35,
            linewidths=0,
            c="black",
        )

    ax.boxplot(
        data,
        positions=box_positions,
        widths=0.5,
        patch_artist=True,
        showfliers=True,
        medianprops=dict(color="black", linewidth=BOX_LINEWIDTH),
        boxprops=dict(facecolor="white", linewidth=BOX_LINEWIDTH),
        whiskerprops=dict(linewidth=BOX_LINEWIDTH),
        capprops=dict(linewidth=BOX_LINEWIDTH),
        flierprops=dict(marker="o", markersize=6, alpha=0.5,
                        markeredgewidth=0, markerfacecolor="black"),
    )

    ax.set_xticks(tick_positions)
    ax.set_xticklabels(tick_labels)
    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)
    ax.set_xlim(-0.5, (len(STRATEGIES) - 1) * GROUP_SPACING + BOX_OFFSET + 0.6)
    ax.set_ylim(-1, max(v.max() for v in data) + 1)

    fig.tight_layout()
    for suffix in (".png", ".pdf"):
        out = base / f"baseline_next_tick_combined{suffix}"
        fig.savefig(out, bbox_inches="tight")
    plt.close(fig)
    print(f"Saved combined plot to {base / 'baseline_next_tick_combined.pdf'}")


if __name__ == "__main__":
    main()
