import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.pyplot import ylim

TITLE_FONTSIZE = 22
AXIS_FONTSIZE = 18
TICK_FONTSIZE = 14

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


def main(base_dir: Path, input_csv: str, output_dir: str):
    groups = read_full_data(base_dir / input_csv)

    fig, ax = plt.subplots(figsize=(24, 25)) # 1.5*16 : 1*25

    rng = np.random.default_rng(42)
    for coverage, values in groups:
        jitter = rng.uniform(-0.22, 0.22, size=len(values))
        ax.scatter(
            np.full(len(values), coverage, dtype=float) + jitter,
            values,
            s=10,
            alpha=0.35,
            linewidths=0,
            c="black",
        )


    positions = [coverage for coverage, _ in groups]
    max_y = max(np.max(values) for _, values in groups)

    ax.set_title(base_dir.name, fontsize=TITLE_FONTSIZE)
    ax.set_xlabel("TSC classes covered", fontsize=AXIS_FONTSIZE)
    ax.set_ylabel("mutants killed", fontsize=AXIS_FONTSIZE)
    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    ax.set_xlim(min(positions) - 1, max(positions) + 2)
    ax.set_ylim(0, 250)#max_y + 5)
    ax.set_xticks(np.arange(0, max(positions) + 1, 10))
    ax.grid(True, axis="y", alpha=0.3)

    fig.tight_layout()
    fig.savefig(base_dir / (output_dir + ".png"), bbox_inches="tight")
    fig.savefig(base_dir / (output_dir + ".pdf"), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved scatter plot to {base_dir / output_dir}")


if __name__ == "__main__":
    for evaluation in Path(__file__).resolve().parent.iterdir():
        if evaluation.is_dir():
            sampleSize = evaluation.name.split("_")[-1]
            for monitorCombination in evaluation.iterdir():
                if monitorCombination.is_dir():
                    csv_files = sorted(monitorCombination.glob("*.csv"))
                    if not csv_files:
                        continue

                    main(monitorCombination, csv_files[0].name, f"scatter_n={sampleSize}")

    print("Finished.")