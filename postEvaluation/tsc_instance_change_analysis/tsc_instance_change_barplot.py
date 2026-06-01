import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 16
AXES_LINEWIDTH = 1.5
BIN_COUNT = 50


def read_times(csv_path: Path) -> np.ndarray:
    times = []
    with csv_path.open(newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            times.append(int(row["millisUntilFirstChange"]))
    return np.array(times, dtype=float)


def main(csv_path: Path):
    times = read_times(csv_path)

    bins = np.linspace(times.min(), times.max(), BIN_COUNT + 1)
    counts, edges = np.histogram(times, bins=bins)
    bin_centers = (edges[:-1] + edges[1:]) / 2
    bin_width = edges[1] - edges[0]

    fig, ax = plt.subplots(figsize=(16, 7))

    ax.bar(bin_centers, counts, width=bin_width * 0.9, color="steelblue", edgecolor="none")

    ax.set_xlabel("Time until first TSC instance change (ms)", fontsize=TICK_FONTSIZE)
    ax.set_ylabel("Count", fontsize=TICK_FONTSIZE)
    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)

    fig.tight_layout()
    fig.savefig(csv_path.with_stem(csv_path.stem + "_barplot").with_suffix(".png"), bbox_inches="tight")
    fig.savefig(csv_path.with_stem(csv_path.stem + "_barplot").with_suffix(".pdf"), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved barplot to {csv_path.with_stem(csv_path.stem + '_barplot').with_suffix('.pdf')}")


if __name__ == "__main__":
    for csv_path in sorted(Path(__file__).resolve().parent.glob("*.csv")):
        main(csv_path)
    print("Finished.")
