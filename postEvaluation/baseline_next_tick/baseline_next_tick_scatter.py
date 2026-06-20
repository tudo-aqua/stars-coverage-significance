#!/usr/bin/env python3
"""Scatter plots for three sampling strategies across all suite sizes.

Auto-discovers size_<n>/ subdirectories and produces:
  - Per size: size_<n>/baseline_next_tick_scatter_<strategy>.[png|pdf]
  - All sizes: baseline_next_tick_scatter_all.[png|pdf]
    Grid layout: rows = strategies (Random / TSC / Leaf), columns = suite sizes.
"""
from __future__ import annotations

import re
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 75
TICK_FONTSIZE_SMALL = 26
AXES_LINEWIDTH = 2.5

STRATEGIES = [
    ("random", "Random"),
    ("tsc", "TSC"),
    ("leaf", "Leaf"),
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

    # Load all data; compute global y-axis max for the all-sizes plot.
    all_data: dict[int, list[np.ndarray]] = {}
    global_max = 0.0
    for size, size_dir in size_dirs:
        arrays = []
        for key, _ in STRATEGIES:
            csv = _find_csv(size_dir, key)
            arr = _read_results(csv) if csv is not None else np.array([], dtype=float)
            arrays.append(arr)
            if arr.size:
                global_max = max(global_max, float(arr.max()))
        all_data[size] = arrays

    # Per-size: one scatter file per strategy, written into the size subdirectory.
    for size, size_dir in size_dirs:
        arrays = all_data[size]
        if not any(arr.size for arr in arrays):
            continue
        local_max = max((arr.max() for arr in arrays if arr.size), default=0.0)
        for (key, label), values in zip(STRATEGIES, arrays):
            if not values.size:
                continue
            fig, ax = plt.subplots(figsize=(24, 24))
            _scatter_ax(ax, values, label, local_max + 1, rng, TICK_FONTSIZE, AXES_LINEWIDTH)
            fig.tight_layout()
            out = size_dir / f"baseline_next_tick_scatter_{key}"
            for suffix in (".png", ".pdf"):
                fig.savefig(out.with_suffix(suffix), bbox_inches="tight")
            plt.close(fig)
        print(f"Saved per-strategy scatter plots to {size_dir.name}/")

    # All-sizes combined: rows = strategies, columns = sizes (only sizes with data).
    populated = [(size, d) for size, d in size_dirs if any(a.size for a in all_data[size])]
    if not populated:
        print("No CSV data found in any size_*/ subdirectory — skipping all-sizes plot.")
        return

    n_rows, n_cols = len(STRATEGIES), len(populated)
    fig, axes = plt.subplots(
        n_rows,
        n_cols,
        figsize=(n_cols * 8, n_rows * 8),
        squeeze=False,
    )
    for col, (size, _) in enumerate(populated):
        for row, ((key, label), values) in enumerate(zip(STRATEGIES, all_data[size])):
            ax = axes[row][col]
            _scatter_ax(
                ax, values, label, global_max + 1, rng, TICK_FONTSIZE_SMALL, AXES_LINEWIDTH
            )
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
    out = base / "baseline_next_tick_scatter_all"
    for suffix in (".png", ".pdf"):
        fig.savefig(out.with_suffix(suffix), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved all-sizes scatter to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
