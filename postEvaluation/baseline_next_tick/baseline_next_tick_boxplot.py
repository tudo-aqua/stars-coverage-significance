#!/usr/bin/env python3
"""Box plots for three sampling strategies across all suite sizes.

Auto-discovers size_<n>/ subdirectories and produces:
  - Per size: size_<n>/baseline_next_tick_boxplot.[png|pdf]
  - All sizes: baseline_next_tick_boxplot_all.[png|pdf]  (one panel per size)
"""
from __future__ import annotations

import re
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 75
TICK_FONTSIZE_SMALL = 26
AXES_LINEWIDTH = 2.5
BOX_LINEWIDTH = 2.5

STRATEGIES = [
    ("random", "Random"),
    ("tsc", "TSC"),
    ("leaf", "Leaf"),
    ("random_scenario", "Random\n(Scenario)"),
    ("tsc_scenario", "TSC\n(Scenario)"),
    ("leaf_scenario", "Leaf\n(Scenario)"),
]


def _discover_sizes(base: Path) -> list[tuple[int, Path]]:
    return sorted(
        [
            (int(m.group(1)), d)
            for d in base.iterdir()
            if d.is_dir() and (m := re.fullmatch(r"size_(\d+)", d.name))
        ],
        key=lambda x: x[0],
    )


def _find_csv(size_dir: Path, key: str) -> Path | None:
    for name in (f"baseline_next_tick_{key}_split.csv", f"baseline_next_tick_{key}.csv"):
        p = size_dir / name
        if p.exists():
            return p
    return None


def _read_results(csv_path: Path) -> np.ndarray:
    values = []
    with csv_path.open() as f:
        next(f)
        for line in f:
            line = line.strip()
            if line:
                values.append(int(line))
    return np.array(values, dtype=float)


def _boxplot_ax(
    ax: plt.Axes,
    data: list[np.ndarray],
    labels: list[str],
    y_max: float,
    tick_fontsize: int,
    linewidth: float,
) -> None:
    ax.boxplot(
        data,
        tick_labels=labels,
        patch_artist=True,
        widths=0.5,
        medianprops=dict(color="black", linewidth=linewidth),
        boxprops=dict(facecolor="white", linewidth=linewidth),
        whiskerprops=dict(linewidth=linewidth),
        capprops=dict(linewidth=linewidth),
        flierprops=dict(marker="o", markersize=6, alpha=0.4, markeredgewidth=0),
    )
    ax.tick_params(axis="both", labelsize=tick_fontsize)
    for spine in ax.spines.values():
        spine.set_linewidth(linewidth)
    ax.set_ylim(-1, y_max + 1)


def main() -> None:
    base = Path(__file__).resolve().parent

    size_dirs = _discover_sizes(base)
    if not size_dirs:
        print("No size_<n>/ subdirectories found.")
        return

    # Load all data; compute global y-axis max for the all-sizes plot.
    all_data: dict[int, tuple[list[np.ndarray], list[str]]] = {}
    global_max = 0.0
    for size, size_dir in size_dirs:
        data, labels = [], []
        for key, label in STRATEGIES:
            csv = _find_csv(size_dir, key)
            if csv is not None:
                arr = _read_results(csv)
                data.append(arr)
                labels.append(label)
                global_max = max(global_max, float(arr.max()))
        all_data[size] = (data, labels)

    # Per-size boxplots.
    for size, size_dir in size_dirs:
        data, labels = all_data[size]
        if not data:
            continue
        local_max = max(v.max() for v in data)
        fig, ax = plt.subplots(figsize=(24, 24))
        _boxplot_ax(ax, data, labels, local_max + 1, TICK_FONTSIZE, BOX_LINEWIDTH)
        fig.tight_layout()
        for suffix in (".png", ".pdf"):
            fig.savefig(size_dir / f"baseline_next_tick_boxplot{suffix}", bbox_inches="tight")
        plt.close(fig)
        print(f"Saved boxplot to {size_dir.name}/")

    # All-sizes combined: one panel per size (only sizes with data).
    populated = [(size, d) for size, d in size_dirs if all_data[size][0]]
    if not populated:
        print("No CSV data found in any size_*/ subdirectory — skipping all-sizes plot.")
        return

    n = len(populated)
    fig, axes = plt.subplots(1, n, figsize=(n * 8, 24), squeeze=False)
    for col, (size, _) in enumerate(populated):
        ax = axes[0][col]
        data, labels = all_data[size]
        _boxplot_ax(ax, data, labels, global_max + 1, TICK_FONTSIZE_SMALL, BOX_LINEWIDTH)
        ax.set_title(
            f"n = {size:,}", fontsize=TICK_FONTSIZE_SMALL + 4, fontweight="bold", pad=12
        )

    fig.tight_layout()
    out = base / "baseline_next_tick_boxplot_all"
    for suffix in (".png", ".pdf"):
        fig.savefig(out.with_suffix(suffix), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved all-sizes boxplot to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
