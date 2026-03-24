#!/usr/bin/env python3
from pathlib import Path
from scipy.stats import linregress

import sys
import math
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt


def exponential_linearity_test(data):
    data_sorted = np.sort(data)[::-1]

    ranks = np.arange(1, len(data_sorted) + 1)
    log_y = np.log(data_sorted)

    print(ranks)
    print(data_sorted)
    print(log_y)

    result = linregress(ranks, log_y)
    log_y_pred = result.intercept + result.slope * ranks
    print(result)

    rmse = float(np.sqrt(np.mean((log_y - log_y_pred) ** 2)))
    print("RMSE: " + str(rmse))


def plot(df, barplot: bool, x_scale: str, y_scale: str, file: Path):
    labels = df.iloc[:, 0].astype(str)
    values = pd.to_numeric(df.iloc[:, 1], errors='coerce').fillna(0)

    width = max(8, math.ceil(0.15 * len(labels)))
    fig, ax = plt.subplots(figsize=(int(width), 6))
    x = list(range(len(labels)))
    if barplot:
        ax.bar(x, values, color='C0', label='Highway Traffic')
    else:
        ax.plot(x, values, marker='o', color='C0', label='Highway Traffic')

    ax.set_xticks(x)
    # Replace x labels by their index, only show every 10th label
    tick_labels = [str(i) if (i % 10 == 0) else '' for i in x]
    ax.set_xticklabels(tick_labels, rotation=90, fontsize=8)
    ax.set_ylabel(df.columns[1] if len(df.columns) > 1 else 'Value')
    ax.set_xscale(x_scale)
    ax.set_yscale(y_scale)
    ax.set_title('Highway traffic - frequency')
    ax.legend()
    fig.tight_layout()

    fig.savefig(file.with_suffix('.png'))
    fig.savefig(file.with_suffix('.pdf'))
    print(f'Saved plot to: {file}')


if __name__ == '__main__':
    csv_path = Path(__file__).parent / "highwayTrafficAnalysisValues.csv"
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1])

    df = pd.read_csv(csv_path)
    df_nonzero = df[pd.to_numeric(df.iloc[:, 1], errors='coerce') != 0]

    plot(df, True, 'linear', 'linear', csv_path.parent / "highwayTrafficAnalysis_xlin_ylin_all.png")
    # plot(df, False,'linear', 'log', csv_path.parent / "highwayTrafficAnalysis_xlin_ylog_all.png")
    # plot(df, False,'log', 'log', csv_path.parent / "highwayTrafficAnalysis_xlog_ylog_all.png")
    plot(df_nonzero, True, 'linear', 'linear', csv_path.parent / "highwayTrafficAnalysis_xlin_ylin.png")
    plot(df_nonzero, False, 'linear', 'log', csv_path.parent / "highwayTrafficAnalysis_xlin_ylog.png")
    plot(df_nonzero, False, 'log', 'log', csv_path.parent / "highwayTrafficAnalysis_xlog_ylog.png")

    values = df_nonzero.iloc[:, 1].to_numpy()
    exponential_linearity_test(values)

