#!/usr/bin/env python3
"""Stacked bar chart: accident rows per leaf node, broken down by mutant.

Reads mutantKillingByLeafNode.csv produced by MutantKillingByLeafNodePostEvaluation.kt.
Each bar = one leaf node; each stacked segment = one mutant.
"""
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


def main() -> None:
    csv_path = Path(__file__).parent / "mutantKillingByLeafNode.csv"
    df = pd.read_csv(csv_path, index_col="mutant_id")

    # Drop mutants with zero accidents across all leaves, then transpose:
    # rows = leaf nodes, columns = mutant IDs
    df = df[df.sum(axis=1) > 0].T

    n_leaves = len(df)
    fig, ax = plt.subplots(figsize=(max(6, n_leaves * 1.2), 6))
    df.plot(kind="bar", stacked=True, ax=ax, width=0.7, legend=False)

    ax.set_xlabel("Leaf Node ID")
    ax.set_ylabel("Rows with next_tick_g0_Accidents_failed = true")
    ax.set_title("Accident Rows per Leaf Node, Broken Down by Mutant")
    ax.tick_params(axis="x", rotation=0)
    n_mutants = len(df.columns)
    ax.annotate(
        f"{n_mutants} mutants (one color each)",
        xy=(0.01, 0.99), xycoords="axes fraction",
        va="top", ha="left", fontsize=8, color="grey",
    )

    fig.tight_layout()
    out_path = Path(__file__).parent / "mutantKillingByLeafNode.png"
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"Plot saved to {out_path}")


if __name__ == "__main__":
    main()
