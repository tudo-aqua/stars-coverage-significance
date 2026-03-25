#!/usr/bin/env python3
from pathlib import Path
from scipy.stats import linregress
from scipy.optimize import curve_fit

import sys
import math
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt


def exponential_linearity_test(data):
    data_sorted = np.sort(data)[::-1]

    ranks = np.arange(1, len(data_sorted) + 1)
    log_y = np.log(data_sorted)

    result = linregress(ranks, log_y)
    log_y_pred = result.intercept + result.slope * ranks
    rmse = float(np.sqrt(np.mean((log_y - log_y_pred) ** 2)))

    print("==================================================")
    print("Exponential linearity test (log(y) vs rank):")
    print("==================================================")
    print(f"slope: {result.slope}")
    print(f"intercept: {result.intercept}")
    print(f"rvalue: {result.rvalue}")
    print(f"r²value: {result.rvalue**2}")
    print(f"pvalue: {result.pvalue}")
    print(f"stderr: {result.stderr}")
    print(f"intercept_stderr: {result.intercept_stderr}")
    print("rmse: " + str(rmse))
    print()
    
def fitExponential(data):
    # Fit y = a * exp(b * x) on descending sorted positive values.
    values = np.asarray(data, dtype=float)
    values = values[np.isfinite(values)]
    values = values[values > 0]

    if values.size < 2:
        print("fitExponential: not enough positive finite data points for fitting.")
        return

    y = np.sort(values)[::-1]
    x = np.arange(1, len(y) + 1, dtype=float)

    def exp_model(x_val, a, b):
        return a * np.exp(b * x_val)

    def fmt_float(value: float, digits: int = 6) -> str:
        return f"{value:,.{digits}f}" if np.isfinite(value) else "nan"

    def fmt_percent(value: float, digits: int = 2) -> str:
        return f"{100.0 * value:.{digits}f}%" if np.isfinite(value) else "nan"

    try:
        # Use first point as amplitude guess and a small negative decay as default.
        p0 = (float(y[0]), -0.01)
        popt, pcov = curve_fit(exp_model, x, y, p0=p0, maxfev=10000)
    except Exception as exc:
        print(f"fitExponential failed: {exc}")
        return

    a, b = float(popt[0]), float(popt[1])
    y_pred = exp_model(x, a, b)

    residuals = y - y_pred
    abs_residuals = np.abs(residuals)
    sse = float(np.sum(residuals ** 2))
    mse = float(np.mean(residuals ** 2))
    rmse = float(np.sqrt(mse))
    mae = float(np.mean(abs_residuals))
    medae = float(np.median(abs_residuals))

    y_mean = float(np.mean(y))
    y_range = float(np.max(y) - np.min(y))
    nrmse_mean = float(rmse / y_mean) if y_mean != 0 else float("nan")
    nrmse_range = float(rmse / y_range) if y_range != 0 else float("nan")
    wape = float(np.sum(abs_residuals) / np.sum(np.abs(y))) if np.sum(np.abs(y)) != 0 else float("nan")

    ss_tot = float(np.sum((y - y_mean) ** 2))
    r2 = float(1.0 - (sse / ss_tot)) if ss_tot != 0 else float("nan")

    with np.errstate(divide="ignore", invalid="ignore"):
        ape = np.abs((residuals / y) * 100.0)
        smape_values = 200.0 * abs_residuals / (np.abs(y) + np.abs(y_pred))

    finite_ape = ape[np.isfinite(ape)]
    mape = float(np.mean(finite_ape)) if finite_ape.size > 0 else float("nan")
    p50_ape = float(np.percentile(finite_ape, 50)) if finite_ape.size > 0 else float("nan")
    p90_ape = float(np.percentile(finite_ape, 90)) if finite_ape.size > 0 else float("nan")

    finite_smape = smape_values[np.isfinite(smape_values)]
    smape = float(np.mean(finite_smape)) if finite_smape.size > 0 else float("nan")

    param_std = np.sqrt(np.diag(pcov)) if pcov.size else np.array([float("nan"), float("nan")])

    # Interpret b in rank-domain terms.
    decay_per_rank = float(1.0 - np.exp(b)) if np.isfinite(b) else float("nan")
    half_life_ranks = float(np.log(0.5) / b) if b < 0 else float("inf")
    e_folding_ranks = float(-1.0 / b) if b < 0 else float("inf")

    print("==================================================")
    print("Exponential fit evaluation:")
    print("==================================================")
    print("Model: y = a * exp(b * x)")
    print(f"a                 : {fmt_float(a)}")
    print(f"b                 : {fmt_float(b)}")
    print(f"a_stderr          : {fmt_float(float(param_std[0]))}")
    print(f"b_stderr          : {fmt_float(float(param_std[1]))}")
    print(f"points            : {len(y)}")
    print("--------------------------------------------------")
    print("Absolute error scale")
    print(f"SSE               : {fmt_float(sse)}")
    print(f"MSE               : {fmt_float(mse)}")
    print(f"RMSE              : {fmt_float(rmse)}")
    print(f"MAE               : {fmt_float(mae)}")
    print(f"MedAE             : {fmt_float(medae)}")
    print("--------------------------------------------------")
    print("Normalized error")
    print(f"R²                : {fmt_float(r2)}")
    print(f"NRMSE(mean)       : {fmt_percent(nrmse_mean)}")
    print(f"NRMSE(range)      : {fmt_percent(nrmse_range)}")
    print(f"WAPE              : {fmt_percent(wape)}")
    print(f"MAPE              : {mape:.2f}%" if np.isfinite(mape) else "MAPE              : nan")
    print(f"sMAPE             : {smape:.2f}%" if np.isfinite(smape) else "sMAPE             : nan")
    print(f"APE p50 / p90     : {p50_ape:.2f}% / {p90_ape:.2f}%" if np.isfinite(p50_ape) and np.isfinite(p90_ape) else "APE p50 / p90     : nan / nan")
    print("--------------------------------------------------")
    print("Decay interpretation")
    print(f"drop per rank     : {fmt_percent(decay_per_rank)}")
    print(f"half-life ranks   : {fmt_float(half_life_ranks)}")
    print(f"e-folding ranks   : {fmt_float(e_folding_ranks)}")
    print()


def plot(df, barplot: bool, x_scale: str, y_scale: str, file: Path):
    labels = df.iloc[:, 0].astype(str)
    values = pd.to_numeric(df.iloc[:, 1], errors='coerce').fillna(0)

    fig, ax = plt.subplots(figsize=(10,10))
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

    # plot(df, True, 'linear', 'linear', csv_path.parent / "highwayTrafficAnalysis_xlin_ylin_all.png")
    # plot(df, False,'linear', 'log', csv_path.parent / "highwayTrafficAnalysis_xlin_ylog_all.png")
    # plot(df, False,'log', 'log', csv_path.parent / "highwayTrafficAnalysis_xlog_ylog_all.png")
    plot(df_nonzero, True, 'linear', 'linear', csv_path.parent / "highwayTrafficAnalysis_xlin_ylin.png")
    plot(df_nonzero, False, 'linear', 'log', csv_path.parent / "highwayTrafficAnalysis_xlin_ylog.png")
    plot(df_nonzero, False, 'log', 'log', csv_path.parent / "highwayTrafficAnalysis_xlog_ylog.png")

    values = df_nonzero.iloc[:, 1].to_numpy()
    exponential_linearity_test(values)
    fitExponential(values)
