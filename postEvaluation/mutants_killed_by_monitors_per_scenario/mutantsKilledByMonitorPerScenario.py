#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
from pandas import DataFrame

CSV_PATH = Path(__file__).with_name("mutantsKilledByMonitorsPerScenario.csv")


def feature_column_name(df: pd.DataFrame) -> str:
    for column in df.columns:
        if column.startswith("Feature ") and column.endswith(" active"):
            return column
    raise ValueError("Could not find feature flag column.")


def to_bool(series: pd.Series) -> pd.Series:
    return series.astype(str).str.strip().str.lower().map(
        {"true": True, "false": False}
    )


def plot_combined(df: DataFrame, monitor: str) -> None:
    x = range(len(df))

    fig, ax_left = plt.subplots(figsize=(14, 6))
    ax_right = ax_left.twinx()

    ax_left.bar(x, df["Frequency in longtail"], alpha=0.6)
    ax_left.set_xlabel("Scenario index (sorted by long-tail frequency)")
    ax_left.set_ylabel("Frequency in longtail")
    ax_left.set_title("Long-tail frequency and count of mutants killed per scenario")

    ax_right.scatter(
        x=df["Scenario"],
        y=df[monitor],
        color="red",
        label=monitor,
    )

    ax_right.set_ylabel("Count of mutants killed")
    ax_right.legend(loc="upper right")

    fig.tight_layout()
    fig.savefig(f"mutantsKilledByMonitorPerScenario_{monitor}.png")
    plt.close(fig)


if __name__ == "__main__":
    df = pd.read_csv(CSV_PATH, skipinitialspace=True)

    for monitor in df.columns[2:]:
        plot_combined(df, monitor)