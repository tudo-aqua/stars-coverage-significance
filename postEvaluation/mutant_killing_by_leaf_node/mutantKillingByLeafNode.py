#!/usr/bin/env python3
"""Stacked bar chart: accident rows per mutant, broken down by leaf node.

Reads mutantKillingByLeafNode.csv produced by MutantKillingByLeafNodePostEvaluation.kt.
Each bar = one mutant; each stacked segment = one accident leaf node.
"""
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


def main() -> None:
    csv_path = Path(__file__).parent / "mutantKillingByLeafNode.csv"
    df = pd.read_csv(csv_path, index_col="mutant_id")

    # Drop mutants with zero accidents across all leaves
    df = df[df.sum(axis=1) > 0]

    n_mutants = len(df)
    fig_width = max(12, n_mutants * 0.15)
    fig, ax = plt.subplots(figsize=(fig_width, 6))
    df.plot(kind="bar", stacked=True, ax=ax, width=0.8)

    ax.set_ylabel("Rows with next_tick_g0_Accidents_failed = true")
    ax.set_title("Accident Rows per Mutant, Broken Down by Leaf Node")

    if n_mutants > 50:
        ax.set_xticks([])
        ax.set_xlabel(f"Mutant ID  ({n_mutants} mutants; labels omitted for readability)")
    else:
        ax.set_xlabel("Mutant ID")

    ax.legend(title="Leaf Node", bbox_to_anchor=(1.01, 1), loc="upper left", borderaxespad=0)

    fig.tight_layout()
    out_path = Path(__file__).parent / "mutantKillingByLeafNode.png"
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"Plot saved to {out_path}")


if __name__ == "__main__":
    main()
