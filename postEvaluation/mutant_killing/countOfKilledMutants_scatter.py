import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

TICK_FONTSIZE = 60
AXES_LINEWIDTH = 4
HEIGHT = 9
WIDTH = 16

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

    fig, ax = plt.subplots(figsize=(WIDTH, HEIGHT))

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

    # mean_points = [
    #     (coverage, float(np.mean(values)))
    #     for coverage, values in groups
    #     if len(values) > 0
    # ]
    # mean_points.sort(key=lambda t: t[0])
    #
    # if mean_points:
    #     mean_x = [p[0] for p in mean_points]
    #     mean_y = [p[1] for p in mean_points]
    #     ax.plot(
    #         mean_x,
    #         mean_y,
    #         color="red",
    #         linewidth=4,
    #         marker="o",
    #         markersize=8,
    #         label="mean",
    #         zorder=5,
    #     )

    positions = [coverage for coverage, _ in groups]

    ax.tick_params(axis="both", labelsize=TICK_FONTSIZE)
    for spine in ax.spines.values():
        spine.set_linewidth(AXES_LINEWIDTH)

    ax.set_xlim(-1,161)
    ax.set_xticks([1,20,40,60,80,100,120,140,160])
    ax.set_yticks([0,20,40,60])

    fig.tight_layout()
    fig.savefig(base_dir / (output_dir + ".png"), bbox_inches="tight")
    fig.savefig(base_dir / (output_dir + ".pdf"), bbox_inches="tight")
    plt.close(fig)
    print(f"Saved scatter plot to {base_dir / output_dir}")


if __name__ == "__main__":
    for evaluation in Path(__file__).resolve().parent.iterdir():
        if evaluation.is_dir():
            if evaluation.name.split("_")[0] == "countOfKilledMutants":
                sampleSize = evaluation.name.split("_")[-1]
                for monitorCombination in evaluation.iterdir():
                    if monitorCombination.is_dir():
                        csv_files = sorted(monitorCombination.glob("*.csv"))
                        if not csv_files:
                            continue

                        main(monitorCombination, csv_files[0].name, f"scatter_n={sampleSize}")

    print("Finished.")