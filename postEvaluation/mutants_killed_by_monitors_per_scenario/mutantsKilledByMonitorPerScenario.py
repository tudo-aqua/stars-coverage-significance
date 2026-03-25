#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
from typing import List

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from pandas import DataFrame

TICK_FONTSIZE = 30
AXES_LINEWIDTH = 1.5

# y = a * e ^ (b * x)
a = 82443573.838401
b = -0.178426

def feature_column_name(df: pd.DataFrame) -> str:
    for column in df.columns:
        if column.startswith("Feature ") and column.endswith(" active"):
            return column
    raise ValueError("Could not find feature flag column.")


def to_bool(series: pd.Series) -> pd.Series:
    return series.astype(str).str.strip().str.lower().map(
        {"true": True, "false": False}
    )


def plot_combined(df: DataFrame, monitors: List[str]) -> None:
    x = df.index

    fig, ax_left = plt.subplots(figsize=(10, 6))
    ax_right = ax_left.twinx()

    ax_left.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax_left.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)

    ax_left.bar(x, df["Frequency in longtail"], alpha=0.6, label="Frequency in longtail")

    x_values = np.asarray(x, dtype=float)
    exponential_curve = a * np.exp(b * x_values)
    ax_left.plot(
        x_values,
        exponential_curve,
        color="darkred",
        linewidth=2.5,
        label="Exponential fit",
    )

    zero_points = df.index[df["Frequency in longtail"].eq(0)]
    if len(zero_points) > 0:
        zero_x = zero_points[0]

        ax_left.axvline(
            x=zero_x,
            color="red",
            linestyle="--",
            linewidth=2,
            label="Long-tail frequency = 0",
        )

        # Label unterhalb der x-Achse bei der vertikalen Linie
        ax_left.text(
            zero_x,
            -.011,
            f"{zero_x}",
            color="red",
            fontsize=TICK_FONTSIZE,
            ha="center",
            va="top",
            transform=ax_left.get_xaxis_transform(),
            clip_on=False,
        )

    ax_left.set_xlabel("Scenario index (sorted by long-tail frequency)")
    ax_left.set_ylabel("Frequency in longtail")
    ax_left.set_xlim(-1,160)
    ax_left.set_title("Long-tail frequency and count of mutants killed per scenario")
    ax_left.legend(loc="upper left")

    markers = [".", "^", "1", "*", "+", "d", "s"]
    for idx, monitor in enumerate(monitors):
        ax_right.scatter(
            x=x,
            y=df[monitor],
            label=monitor,
            marker=markers[idx % len(markers)],
        )

    ax_right.set_ylabel("Count of mutants killed")
    ax_right.legend(loc="upper right")

    fig.tight_layout()
    fig.savefig(Path(__file__).parent / f"mutantsKilledByMonitorPerScenario_{monitors}.png")
    fig.savefig(Path(__file__).parent / f"mutantsKilledByMonitorPerScenario_{monitors}.pdf")
    plt.close(fig)


if __name__ == "__main__":
    df = pd.read_csv(Path(__file__).with_name("mutantsKilledByMonitorsPerScenario.csv"), skipinitialspace=True)

    for monitor in df.columns[2:]:
        plot_combined(df, [monitor])

    plot_combined(df, ['G0Accidents', 'G1SafeDistance', 'G4TrafficFlow', 'I2FasterThanLeftTraffic'])
    plot_combined(df, ['G0Accidents', 'G1SafeDistance', 'G2EmergencyBraking', 'G3MaximumSpeed', 'G4TrafficFlow', 'I1Stopping', 'I2FasterThanLeftTraffic'])