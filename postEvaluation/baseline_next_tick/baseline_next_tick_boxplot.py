#!/usr/bin/env python3
"""Box plots for sampling strategies across all suite sizes.

Auto-discovers size_<n>/ subdirectories and produces:
  - Per size (full strategies):  size_<n>/baseline_next_tick_boxplot.[png|pdf]
  - Per size (rare strategies):  size_<n>/baseline_next_tick_boxplot_rare.[png|pdf]
  - All sizes (full strategies): baseline_next_tick_boxplot_all.[png|pdf]
  - All sizes (rare strategies): baseline_next_tick_boxplot_all_rare.[png|pdf]
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

STRATEGIES_FULL = [
    ("random", "Random"),
    ("tsc", "TSC"),
    ("leaf", "Leaf"),
    ("random_scenario", "Random\n(Scenario)"),
    ("tsc_scenario", "TSC\n(Scenario)"),
    ("leaf_scenario", "Leaf\n(Scenario)"),
    ("leaf_scenario_accidents", "Leaf\n(Scenario,\nAccidents)"),
]

STRATEGIES_RARE = [
    ("random_scenario_rare", "Random\n(Scenario,\nRare)"),
    ("tsc_scenario_rare", "TSC\n(Scenario,\nRare)"),
    ("leaf_scenario_rare", "Leaf\n(Scenario,\nRare)"),
    ("leaf_scenario_accidents_rare", "Leaf\n(Scenario,\nAccidents,\nRare)"),
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

    # Per-size boxplots — one per strategy group.
    for size, size_dir in size_dirs:
        for group_suffix, group_strategies in STRATEGY_GROUPS:
            data = [all_data[size][key] for key, _ in group_strategies if key in all_data[size]]
            labels = [label for key, label in group_strategies if key in all_data[size]]
            if not data:
                continue
            local_max = max(v.max() for v in data)
            fig, ax = plt.subplots(figsize=(24, 24))
            _boxplot_ax(ax, data, labels, local_max + 1, TICK_FONTSIZE, BOX_LINEWIDTH)
            fig.tight_layout()
            for ext in (".png", ".pdf"):
                fig.savefig(
                    size_dir / f"baseline_next_tick_boxplot{group_suffix}{ext}",
                    bbox_inches="tight",
                )
            plt.close(fig)
        print(f"Saved boxplots to {size_dir.name}/")

    # All-sizes combined: one panel per size, one figure per strategy group.
    for group_suffix, group_strategies in STRATEGY_GROUPS:
        populated = [
            (size, d)
            for size, d in size_dirs
            if any(key in all_data[size] for key, _ in group_strategies)
        ]
        if not populated:
            print(f"No CSV data for group '{group_suffix}' — skipping all-sizes boxplot.")
            continue

        n = len(populated)
        fig, axes = plt.subplots(1, n, figsize=(n * 8, 24), squeeze=False)
        gmax = group_global_max[group_suffix]
        for col, (size, _) in enumerate(populated):
            ax = axes[0][col]
            data = [all_data[size][key] for key, _ in group_strategies if key in all_data[size]]
            labels = [label for key, label in group_strategies if key in all_data[size]]
            _boxplot_ax(ax, data, labels, gmax + 1, TICK_FONTSIZE_SMALL, BOX_LINEWIDTH)
            ax.set_title(
                f"n = {size:,}", fontsize=TICK_FONTSIZE_SMALL + 4, fontweight="bold", pad=12
            )

        fig.tight_layout()
        out = base / f"baseline_next_tick_boxplot_all{group_suffix}"
        for ext in (".png", ".pdf"):
            fig.savefig(out.with_suffix(ext), bbox_inches="tight")
        plt.close(fig)
        print(f"Saved all-sizes boxplot to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
