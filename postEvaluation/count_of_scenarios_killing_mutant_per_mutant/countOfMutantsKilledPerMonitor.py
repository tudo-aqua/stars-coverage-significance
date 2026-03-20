#!/usr/bin/env python3
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt


def main() -> None:
    csv_path = Path(__file__).parent / "countOfScenariosKillingMutantPerMutant.csv"
    df = pd.read_csv(csv_path)
    df.columns = df.columns.str.strip()

    x = range(len(df))
    y = df.iloc[:, 1]

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.scatter(x, y, label="Count of Scenarios Killing the Mutant")
    ax.set_xlabel("Mutant")
    ax.set_ylabel("Count of Scenarios")
    ax.legend()

    fig.tight_layout()
    fig.savefig(Path(__file__).parent / "countOfScenariosKillingMutantPerMutant.png")
    plt.close(fig)


if __name__ == '__main__':
    main()
