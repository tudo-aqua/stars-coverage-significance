#!/usr/bin/env python3
"""Combined scatter + box plots for sampling strategies across all suite sizes.

Auto-discovers size_<n>/ subdirectories and produces:
  - Per size (full strategies):  size_<n>/baseline_next_tick_combined.[png|pdf]
  - Per size (rare strategies):  size_<n>/baseline_next_tick_combined_rare.[png|pdf]
  - All sizes (full strategies): baseline_next_tick_combined_all.[png|pdf]
  - All sizes (rare strategies): baseline_next_tick_combined_all_rare.[png|pdf]

Each panel shows scatter (individual repetition values with jitter) directly to the
left of the box plot so the full distribution and its summary are visible side by side.
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
    ("random_scenario_replacement", "Random\n(Scenario,\nRepl.)"),
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

# Within each strategy group: scatter at 0.0, box at 0.7; groups spaced 2.5 apart.
GROUP_SPACING = 2.5
SCATTER_OFFSET = 0.0
BOX_OFFSET = 0.7


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


def _combined_ax(
    ax: plt.Axes,
    data: list[np.ndarray],
    labels: list[str],
    y_max: float,
    rng: np.random.Generator,
    tick_fontsize: int,
    linewidth: float,
) -> None:
    box_positions = []
    tick_positions = []

    for i, (values, label) in enumerate(zip(data, labels)):
        sx = i * GROUP_SPACING + SCATTER_OFFSET
        bx = i * GROUP_SPACING + BOX_OFFSET
        box_positions.append(bx)
        tick_positions.append((sx + bx) / 2)

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
        medianprops=dict(color="black", linewidth=linewidth),
        boxprops=dict(facecolor="white", linewidth=linewidth),
        whiskerprops=dict(linewidth=linewidth),
        capprops=dict(linewidth=linewidth),
        flierprops=dict(
            marker="o", markersize=6, alpha=0.5, markeredgewidth=0, markerfacecolor="black"
        ),
    )

    ax.set_xticks(tick_positions)
    ax.set_xticklabels(labels)
    ax.tick_params(axis="both", labelsize=tick_fontsize)
    for spine in ax.spines.values():
        spine.set_linewidth(linewidth)
    ax.set_xlim(-0.5, (len(data) - 1) * GROUP_SPACING + BOX_OFFSET + 0.6)
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

    # Per-size combined scatter + box plots — one per strategy group.
    for size, size_dir in size_dirs:
        for group_suffix, group_strategies in STRATEGY_GROUPS:
            data = [all_data[size][key] for key, _ in group_strategies if key in all_data[size]]
            labels = [label for key, label in group_strategies if key in all_data[size]]
            if not data:
                continue
            local_max = max(v.max() for v in data)
            fig, ax = plt.subplots(figsize=(36, 24))
            _combined_ax(ax, data, labels, local_max + 1, rng, TICK_FONTSIZE, BOX_LINEWIDTH)
            fig.tight_layout()
            for ext in (".png", ".pdf"):
                fig.savefig(
                    size_dir / f"baseline_next_tick_combined{group_suffix}{ext}",
                    bbox_inches="tight",
                )
            plt.close(fig)
        print(f"Saved combined plots to {size_dir.name}/")

    # All-sizes combined: one panel per size, one figure per strategy group.
    for group_suffix, group_strategies in STRATEGY_GROUPS:
        populated = [
            (size, d)
            for size, d in size_dirs
            if any(key in all_data[size] for key, _ in group_strategies)
        ]
        if not populated:
            print(f"No CSV data for group '{group_suffix}' — skipping all-sizes combined plot.")
            continue

        n = len(populated)
        fig, axes = plt.subplots(1, n, figsize=(n * 12, 24), squeeze=False)
        gmax = group_global_max[group_suffix]
        for col, (size, _) in enumerate(populated):
            ax = axes[0][col]
            data = [all_data[size][key] for key, _ in group_strategies if key in all_data[size]]
            labels = [label for key, label in group_strategies if key in all_data[size]]
            _combined_ax(ax, data, labels, gmax + 1, rng, TICK_FONTSIZE_SMALL, BOX_LINEWIDTH)
            ax.set_title(
                f"n = {size:,}", fontsize=TICK_FONTSIZE_SMALL + 4, fontweight="bold", pad=12
            )

        fig.tight_layout()
        out = base / f"baseline_next_tick_combined_all{group_suffix}"
        for ext in (".png", ".pdf"):
            fig.savefig(out.with_suffix(ext), bbox_inches="tight")
        plt.close(fig)
        print(f"Saved all-sizes combined to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
