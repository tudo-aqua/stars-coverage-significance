import csv
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

INPUT_CSV = "countOfKilledMutants_full_data.csv"
OUTPUT_PNG = "countOfKilledMutants_scatter.png"


def read_full_data(csv_path: Path):
    groups = []
    with csv_path.open(newline="") as f:
        reader = csv.reader(f, skipinitialspace=True)
        next(reader)  # header
        for row in reader:
            coverage = int(row[0])
            values = np.array([float(x) for x in row[1:] if x.strip() != ""], dtype=float)
            groups.append((coverage, values))
    return groups


def main(monitorCombination: Path):
    groups = read_full_data(monitorCombination / INPUT_CSV)

    fig, ax = plt.subplots(figsize=(30, 10))

    rng = np.random.default_rng(42)
    for coverage, values in groups:
        jitter = rng.uniform(-0.22, 0.22, size=len(values))
        ax.scatter(
            np.full(len(values), coverage, dtype=float) + jitter,
            values,
            s=10,
            alpha=0.35,
            linewidths=0,
        )

    positions = [coverage for coverage, _ in groups]
    max_y = max(np.max(values) for _, values in groups)

    ax.set_title(monitorCombination.suffix)
    ax.set_xlabel("# TSC classes covered")
    ax.set_ylabel("# mutants killed")
    ax.set_xlim(min(positions) - 1, max(positions) + 2)
    ax.set_ylim(0, max_y + 5)
    ax.set_xticks(np.arange(0, max(positions) + 1, 10))
    ax.grid(True, axis="y", alpha=0.3)

    fig.tight_layout()
    fig.savefig(monitorCombination / OUTPUT_PNG, dpi=200, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    for monitorCombination in Path(__file__).resolve().parent.iterdir():
        if monitorCombination.is_dir():
            main(monitorCombination)
