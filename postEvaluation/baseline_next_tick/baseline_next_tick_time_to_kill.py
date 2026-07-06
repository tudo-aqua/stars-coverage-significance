#!/usr/bin/env python3
"""Box plots for time-to-first-kill across sampling strategies, one per mutant.

Reads CSVs from:
  baseline_next_tick/time_to_kill/mutant_<id>/ttk_{random,tsc,leaf,leaf_accidents}.csv

Produces:
  - Per mutant: time_to_kill/mutant_<id>/ttk_boxplot.[png|pdf]
  - Combined:   time_to_kill/ttk_combined.[png|pdf]  (all mutants side by side)

Each CSV has a single column of draw counts (one per repetition). -1 entries (pool exhausted
without kill) are silently excluded.
"""
from __future__ import annotations

import re
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 60
TICK_FONTSIZE_SMALL = 22
BOX_LINEWIDTH = 2.5

STRATEGIES = [
    ("random", "Random"),
    ("tsc", "TSC"),
    ("leaf", "Leaf"),
    ("leaf_accidents", "Leaf\n(Accidents)"),
]


def _discover_mutants(base: Path) -> list[tuple[int, Path]]:
    return sorted(
        [
            (int(m.group(1)), d)
            for d in base.iterdir()
            if d.is_dir() and (m := re.fullmatch(r"mutant_(\d+)", d.name))
        ],
        key=lambda x: x[0],
    )


def _read_ttk(csv_path: Path) -> np.ndarray:
    values = []
    with csv_path.open() as f:
        next(f)
        for line in f:
            line = line.strip()
            if line:
                val = int(line)
                if val > 0:
                    values.append(val)
    return np.array(values, dtype=float)


def _boxplot_ax(
    ax: plt.Axes,
    data: list[np.ndarray],
    labels: list[str],
    y_max: float,
    tick_fontsize: int,
    linewidth: float,
    title: str | None = None,
    ylabel: bool = False,
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
    ax.set_ylim(0, y_max * 1.05)
    if title:
        ax.set_title(title, fontsize=tick_fontsize, fontweight="bold", pad=10)
    if ylabel:
        ax.set_ylabel("Scenarios to first kill", fontsize=tick_fontsize, labelpad=10)


def main() -> None:
    base = Path(__file__).resolve().parent / "time_to_kill"
    if not base.exists():
        print(f"Directory {base} not found — run evaluateTimeToKill() first.")
        return

    mutant_dirs = _discover_mutants(base)
    if not mutant_dirs:
        print("No mutant_<id>/ subdirectories found.")
        return

    all_data: dict[int, dict[str, np.ndarray]] = {}
    global_max = 0.0
    for mutant_id, mutant_dir in mutant_dirs:
        data: dict[str, np.ndarray] = {}
        for key, _ in STRATEGIES:
            p = mutant_dir / f"ttk_{key}.csv"
            if p.exists():
                arr = _read_ttk(p)
                if arr.size > 0:
                    data[key] = arr
                    global_max = max(global_max, float(arr.max()))
        if data:
            all_data[mutant_id] = data

    if not all_data:
        print("No data found.")
        return

    # Per-mutant box plots
    for mutant_id, mutant_dir in mutant_dirs:
        if mutant_id not in all_data:
            continue
        data_dict = all_data[mutant_id]
        present = [(k, l) for k, l in STRATEGIES if k in data_dict]
        if not present:
            continue
        values = [data_dict[k] for k, _ in present]
        labels = [l for _, l in present]
        local_max = max(v.max() for v in values)
        fig, ax = plt.subplots(figsize=(16, 16))
        _boxplot_ax(
            ax,
            values,
            labels,
            local_max,
            TICK_FONTSIZE,
            BOX_LINEWIDTH,
            title=f"Mutant {mutant_id}",
            ylabel=True,
        )
        fig.tight_layout()
        for ext in (".png", ".pdf"):
            fig.savefig(mutant_dir / f"ttk_boxplot{ext}", bbox_inches="tight")
        plt.close(fig)
    print("Saved per-mutant box plots.")

    # Combined figure — all mutants side by side with a shared y-axis scale
    populated = [(mid, d) for mid, d in mutant_dirs if mid in all_data]
    n = len(populated)
    fig, axes = plt.subplots(1, n, figsize=(max(n * 7, 14), 20), squeeze=False)
    for col, (mutant_id, _) in enumerate(populated):
        ax = axes[0][col]
        data_dict = all_data[mutant_id]
        present = [(k, l) for k, l in STRATEGIES if k in data_dict]
        values = [data_dict[k] for k, _ in present]
        labels = [l for _, l in present]
        _boxplot_ax(
            ax,
            values,
            labels,
            global_max,
            TICK_FONTSIZE_SMALL,
            BOX_LINEWIDTH,
            title=f"Mutant {mutant_id}",
            ylabel=(col == 0),
        )
    fig.tight_layout()
    out = base / "ttk_combined"
    for ext in (".png", ".pdf"):
        fig.savefig(out.with_suffix(ext), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved combined plot to {out.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
