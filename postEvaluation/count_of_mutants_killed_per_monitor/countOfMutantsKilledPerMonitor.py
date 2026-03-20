#!/usr/bin/env python3
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt


def main() -> None:
    csv_path = Path(__file__).parent / "countOfMutantsKilledPerMonitor.csv"
    df = pd.read_csv(csv_path)
    values = df.iloc[0].sort_values(ascending=False)

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.bar(values.index, values.values)
    ax.set_ylabel("Killed mutants")
    ax.set_xticks(range(len(values.index)))
    ax.set_xticklabels(values.index, rotation=45, ha="right")

    fig.tight_layout()
    fig.savefig(Path(__file__).parent / "countOfMutantsKilledPerMonitor.png")
    plt.close(fig)


if __name__ == '__main__':
    main()
