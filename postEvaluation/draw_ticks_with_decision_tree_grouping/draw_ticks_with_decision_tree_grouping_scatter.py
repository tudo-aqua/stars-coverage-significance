#!/usr/bin/env python3
"""Scatter plots for tick-sampling strategies across all suite sizes.

Auto-discovers size_<n>/ subdirectories and produces:
  - Per size: size_<n>/draw_ticks_scatter_<strategy>.[png|pdf]
  - All sizes (full strategies):  draw_ticks_scatter_all.[png|pdf]
  - All sizes (rare strategies):  draw_ticks_scatter_all_rare.[png|pdf]
    Grid layout: rows = strategies, columns = suite sizes.

Reads CSVs produced by DrawTicksWithDecisionTreeGroupingPostEvaluation.evaluate() (see
RunDrawTicksWithDecisionTreeGrouping.kt), which samples individual ticks directly rather than whole
starting scenarios (contrast with baseline_next_tick_scatter.py's scenario-based strategies).
"""
from __future__ import annotations

import re
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 75
TICK_FONTSIZE_SMALL = 26
AXES_LINEWIDTH = 2.5

STRATEGIES_FULL = [
    ("random_tick", "Random"),
    ("leaf_tick", "Leaf"),
    ("leaf_tick_weighted", "Leaf\n(Weighted)"),
    ("leaf_tick_alternating", "Leaf\n(Alternating)"),
]

STRATEGIES_RARE = [
    ("random_tick_rare", "Random\n(Rare)"),
    ("leaf_tick_rare", "Leaf\n(Rare)"),
    ("leaf_tick_weighted_rare", "Leaf\n(Weighted,\nRare)"),
    ("leaf_tick_alternating_rare", "Leaf\n(Alternating,\nRare)"),
]

STRATEGIES = STRATEGIES_FULL + STRATEGIES_RARE
STRATEGY_GROUPS = [("", STRATEGIES_FULL), ("_rare", STRATEGIES_RARE)]


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
    for name in (f"draw_ticks_{key}_split.csv", f"draw_ticks_{key}.csv"):
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


def _scatter_ax(
    ax: plt.Axes,
    values: np.ndarray,
    label: str,
    y_max: float,
    rng: np.random.Generator,
    tick_fontsize: int,
    linewidth: float,
) -> None:
    jitter = rng.uniform(-0.22, 0.22, size=len(values))
    ax.scatter(
        np.zeros(len(values)) + jitter, values, s=100, alpha=0.35, linewidths=0, c="black"
    )
    ax.set_xticks([0])
    ax.set_xticklabels([label])
    ax.tick_params(axis="both", labelsize=tick_fontsize)
    for spine in ax.spines.values():
        spine.set_linewidth(linewidth)
    ax.set_xlim(-0.5, 0.5)
    ax.set_ylim(-1, y_max + 1)


def main() -> None:
    base = Path(__file__).resolve().parent
    rng = np.random.default_rng(42)

    size_dirs = _discover_sizes(base)
    if not size_dirs:
        print("No size_<n>/ subdirectories found.")
        return

    # Load all data keyed by strategy key; track per-group global y-axis max.
    all_data: dict[int, dict[str, np.ndarray]] = {}
    group_global_max: dict[str, float] = {suffix: 0.0 for suffix, _ in STRATEGY_GROUPS}
    for size, size_dir in size_dirs:
        data: dict[str, np.ndarray] = {}
        for key, _ in STRATEGIES:
            csv = _find_csv(size_dir, key)
            if csv is not None:
                arr = _read_results(csv)
                data[key] = arr
                for suffix, group in STRATEGY_GROUPS:
                    if any(k == key for k, _ in group):
                        group_global_max[suffix] = max(group_global_max[suffix], float(arr.max()))
        all_data[size] = data

    # Per-size: one scatter file per strategy (individual, unchanged).
    for size, size_dir in size_dirs:
        data = all_data[size]
        if not data:
            continue
        local_max = max((v.max() for v in data.values()), default=0.0)
        for key, label in STRATEGIES:
            values = data.get(key)
            if values is None or not values.size:
                continue
            fig, ax = plt.subplots(figsize=(24, 24))
            _scatter_ax(ax, values, label, local_max + 1, rng, TICK_FONTSIZE, AXES_LINEWIDTH)
            fig.tight_layout()
            out = size_dir / f"draw_ticks_scatter_{key}"
            for ext in (".png", ".pdf"):
                fig.savefig(out.with_suffix(ext), bbox_inches="tight")
            plt.close(fig)
        print(f"Saved per-strategy scatter plots to {size_dir.name}/")

    # All-sizes combined: one grid per strategy group.
    for group_suffix, group_strategies in STRATEGY_GROUPS:
        populated = [
            (size, d)
            for size, d in size_dirs
            if any(key in all_data[size] for key, _ in group_strategies)
        ]
        if not populated:
            print(f"No CSV data for group '{group_suffix}' — skipping all-sizes plot.")
            continue

        n_rows = len(group_strategies)
        n_cols = len(populated)
        fig, axes = plt.subplots(
            n_rows,
            n_cols,
            figsize=(n_cols * 8, n_rows * 8),
            squeeze=False,
        )
        gmax = group_global_max[group_suffix]
        for col, (size, _) in enumerate(populated):
            for row, (key, label) in enumerate(group_strategies):
                ax = axes[row][col]
                values = all_data[size].get(key, np.array([], dtype=float))
                _scatter_ax(ax, values, label, gmax + 1, rng, TICK_FONTSIZE_SMALL, AXES_LINEWIDTH)
                if row == 0:
                    ax.set_title(
                        f"n = {size:,}",
                        fontsize=TICK_FONTSIZE_SMALL + 4,
                        fontweight="bold",
                        pad=12,
                    )
                if col == 0:
                    ax.set_ylabel(label, fontsize=TICK_FONTSIZE_SMALL + 4, labelpad=12)

        fig.tight_layout()
        out = base / f"draw_ticks_scatter_all{group_suffix}"
        for ext in (".png", ".pdf"):
            fig.savefig(out.with_suffix(ext), bbox_inches="tight")
        plt.close(fig)
        print(f"Saved all-sizes scatter to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
