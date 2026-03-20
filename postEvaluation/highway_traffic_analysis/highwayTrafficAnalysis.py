#!/usr/bin/env python3
from pathlib import Path
import sys
import math

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt


def _irls_log_fit(x_fit: np.ndarray, y_fit: np.ndarray, maxiter: int = 20) -> tuple[float, float]:
    """Robust log-space linear fit using IRLS (returns A, B for y = A * exp(B x)).
    Uses Tukey-like bisquare weights on residuals in log-space.
    """
    ylog = np.log(y_fit)
    # initialize intercept/B from an ordinary fit to ensure they exist
    try:
        init_coeffs = np.polyfit(x_fit, ylog, 1)
        B = float(init_coeffs[0])
        intercept = float(init_coeffs[1])
    except Exception:
        B = 0.0
        intercept = float(np.mean(ylog))
    w = np.ones_like(ylog)
    for _ in range(maxiter):
        # weighted linear fit on ylog = B * x + intercept
        try:
            coeffs = np.polyfit(x_fit, ylog, 1, w=w)
        except Exception:
            coeffs = np.polyfit(x_fit, ylog, 1)
        B = float(coeffs[0])
        intercept = float(coeffs[1])
        pred = B * x_fit + intercept
        r = ylog - pred
        # robust scale estimate (MAD)
        mad = np.median(np.abs(r))
        if mad <= 0:
            break
        u = r / (6.0 * mad)  # tuning constant 6 roughly scales residuals
        # bisquare weights
        w_new = np.square(1 - np.square(u))
        w_new[np.abs(u) >= 1] = 0.0
        # avoid all-zero weights
        if np.all(w_new == 0):
            break
        # check convergence
        if np.allclose(w, w_new, rtol=1e-3, atol=1e-6):
            break
        w = w_new
    logA = intercept
    A = float(np.exp(logA))
    return A, B


def main(include_zeros: bool = True) -> None:
    csv_path = Path(__file__).parent / "highwayTrafficAnalysisValues.csv"
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1])

    df = pd.read_csv(csv_path)

    # If include_zeros is False, filter out rows where the value (second column) is 0
    if not include_zeros:
        df = df[pd.to_numeric(df.iloc[:, 1], errors='coerce') != 0]

    labels = df.iloc[:, 0].astype(str)
    values = pd.to_numeric(df.iloc[:, 1], errors='coerce').fillna(0)

    # Standard vertical bar plot
    width = max(8, math.ceil(0.15 * len(labels)))
    fig, ax = plt.subplots(figsize=(int(width), 6))
    x = list(range(len(labels)))
    ax.bar(x, values, color='C0', label='data')

    x_arr = np.array(x, dtype=float)
    max_plot = 100000

    # Compute exponential fit to the actual bar data: y = A * exp(B * x)
    # Use only positive y values for log regression
    positive_mask = values > 0
    A = None
    B = None
    if positive_mask.sum() >= 2:
        x_fit = x_arr[positive_mask]
        y_fit = values[positive_mask]
        pos_idx = np.where(positive_mask)[0]

        method = 'none'
        fitted = False

        # Scale x to improve numeric stability (fit on x_scaled = x / scale)
        scale = float(max(1.0, x_fit.max()))
        x_fit_scaled = x_fit / scale

        # Try robust nonlinear least squares using scipy.least_squares on scaled x if available
        try:
            from scipy.optimize import least_squares

            # First try a least-squares fit in log-space (fit logA and B_scaled) to reduce influence
            def _residuals_log(params):
                logA_, B_scaled = params
                return (logA_ + B_scaled * x_fit_scaled) - np.log(y_fit)

            # initial guess from log-linear regression on scaled x
            try:
                coeffs_init = np.polyfit(x_fit_scaled, np.log(y_fit), 1)
                B0s = float(coeffs_init[0])
                logA0 = float(coeffs_init[1])
                p0_log = np.array([logA0, B0s], dtype=float)
            except Exception:
                p0_log = np.array([np.log(max(y_fit)), -0.1], dtype=float)

            try:
                res_log = least_squares(_residuals_log, p0_log, loss='soft_l1', max_nfev=10000)
                if res_log.success:
                    logA_fit = float(res_log.x[0])
                    B_scaled_fit = float(res_log.x[1])
                    A = float(np.exp(logA_fit))
                    B = float(B_scaled_fit) / scale
                    method = 'least_squares_log_soft_l1_scaled'
                    fitted = True
            except Exception:
                fitted = False

            if not fitted:
                # fallback: original-space residuals (less robust to scale)
                def _residuals(params):
                    A_, B_ = params
                    return A_ * np.exp(B_ * x_fit_scaled) - y_fit

                # initial guess from log-linear regression on scaled x
                try:
                    coeffs_init = np.polyfit(x_fit_scaled, np.log(y_fit), 1)
                    B0s = float(coeffs_init[0])
                    logA0 = float(coeffs_init[1])
                    A0 = float(np.exp(logA0))
                    p0 = np.array([A0 if A0 > 0 else max(y_fit), B0s], dtype=float)
                except Exception:
                    p0 = np.array([max(y_fit), -0.1], dtype=float)

                lb = [0.0, -np.inf]
                ub = [np.inf, np.inf]
                try:
                    res = least_squares(_residuals, p0, bounds=(lb, ub), loss='soft_l1', max_nfev=10000)
                    if res.success:
                        A = float(res.x[0])
                        B = float(res.x[1]) / scale  # convert back to original x scale
                        method = 'least_squares_soft_l1_scaled'
                        fitted = True
                except Exception:
                    fitted = False
        except Exception:
            fitted = False

        # Try nonlinear curve_fit on scaled x as a second option (less robust)
        if not fitted:
            try:
                from scipy.optimize import curve_fit

                def _exp_model_scaled(xv, A_, B_s):
                    return A_ * np.exp(B_s * xv)

                try:
                    coeffs_init = np.polyfit(x_fit_scaled, np.log(y_fit), 1)
                    B0s = float(coeffs_init[0])
                    logA0 = float(coeffs_init[1])
                    A0 = float(np.exp(logA0))
                    p0 = [A0 if A0 > 0 else max(y_fit), B0s]
                except Exception:
                    p0 = [max(y_fit), -0.1]

                res = curve_fit(_exp_model_scaled, x_fit_scaled, y_fit, p0=p0, bounds=([0.0, -np.inf], [np.inf, np.inf]), maxfev=10000)
                popt = res[0]
                A = float(popt[0])
                B = float(popt[1]) / scale
                method = 'curve_fit_scaled'
                fitted = True
            except Exception:
                fitted = False

        # Try IRLS in log-space on scaled x as a robust alternative
        if not fitted:
            try:
                # reuse _irls_log_fit but on scaled x
                A_irls, B_irls = _irls_log_fit(x_fit_scaled, y_fit, maxiter=50)
                A = A_irls
                B = float(B_irls) / scale
                method = 'irls_log_scaled'
                fitted = True
            except Exception:
                fitted = False

        # Fallbacks: weighted log-linear on scaled x, then centered log-linear
        if not fitted:
            try:
                weights = np.sqrt(y_fit)
                coeffs = np.polyfit(x_fit_scaled, np.log(y_fit), 1, w=weights)
                B = float(coeffs[0]) / scale
                logA = float(coeffs[1])
                A = float(np.exp(logA))
                method = 'weighted_log_scaled'
                fitted = True
            except Exception:
                fitted = False

        if not fitted:
            x_mean = np.mean(x_fit_scaled)
            coeffs = np.polyfit(x_fit_scaled - x_mean, np.log(y_fit), 1)
            B = float(coeffs[0]) / scale
            intercept_center = float(coeffs[1])
            logA = intercept_center + B * (x_mean * scale)
            A = float(np.exp(logA))
            method = 'centered_log_scaled'

        # compute rmse for the exp fit on the fitted points
        exp_data_vals = A * np.exp(B * x_arr)
        rmse_exp = float(np.sqrt(np.mean((exp_data_vals[positive_mask] - y_fit) ** 2)))

        # Refinement: try fitting only to the larger values (tails) at several percentiles
        best_local_rmse = rmse_exp
        best_local_A = A
        best_local_B = B
        best_local_method = method
        best_local_global_mask = None
        for p in (50, 60, 70, 80, 90):
            try:
                thresh = np.percentile(y_fit, p)
                mask_p = y_fit >= thresh
                if mask_p.sum() < 2:
                    continue
                x_sub = x_fit_scaled[mask_p] if 'x_fit_scaled' in locals() else x_fit[mask_p]
                y_sub = y_fit[mask_p]
                # try curve_fit if available
                fitted_p = False
                A_p = None
                B_p = None
                try:
                    from scipy.optimize import curve_fit

                    def _exp_model_sub(xv, A_, B_):
                        return A_ * np.exp(B_ * xv)

                    # initial guess from log-linear on subset
                    try:
                        coeffs_init = np.polyfit(x_sub, np.log(y_sub), 1)
                        B0s = float(coeffs_init[0])
                        logA0 = float(coeffs_init[1])
                        A0 = float(np.exp(logA0))
                        p0 = [A0 if A0 > 0 else max(y_sub), B0s]
                    except Exception:
                        p0 = [max(y_sub), -0.1]

                    res = curve_fit(_exp_model_sub, x_sub, y_sub, p0=p0, bounds=([0.0, -np.inf], [np.inf, np.inf]), maxfev=10000)
                    popt = res[0]
                    A_p = float(popt[0])
                    B_p = float(popt[1])
                    # if we fitted on scaled x, convert B back
                    if 'x_fit_scaled' in locals():
                        B_p = B_p / float(max(1.0, x_fit.max()))
                    fitted_p = True
                except Exception:
                    fitted_p = False

                if not fitted_p:
                    # weighted log-linear on subset
                    try:
                        weights_sub = np.sqrt(y_sub)
                        coeffs = np.polyfit(x_sub, np.log(y_sub), 1, w=weights_sub)
                        B_p = float(coeffs[0])
                        logA_p = float(coeffs[1])
                        A_p = float(np.exp(logA_p))
                        if 'x_fit_scaled' in locals():
                            B_p = B_p / float(max(1.0, x_fit.max()))
                        fitted_p = True
                    except Exception:
                        fitted_p = False

                if not fitted_p:
                    continue

                # Evaluate RMSE on the subset (on original scale x_fit)
                if A_p is None or B_p is None:
                    continue
                pred_p = A_p * np.exp(B_p * x_fit[mask_p])
                rmse_p = float(np.sqrt(np.mean((pred_p - y_sub) ** 2)))
                if rmse_p < best_local_rmse * 0.9:  # require ~10% improvement
                    best_local_rmse = rmse_p
                    best_local_A = A_p
                    best_local_B = B_p
                    best_local_method = f'{method}+tail{p}'
                    # store global indices of this subset
                    best_local_global_mask = pos_idx[mask_p]
            except Exception:
                continue

        # If a better tail-fit was found adopt it
        if best_local_rmse < rmse_exp * 0.9:
            A = best_local_A
            B = best_local_B
            rmse_exp = best_local_rmse
            method = best_local_method
            fit_global_idx = best_local_global_mask if best_local_global_mask is not None else pos_idx
        else:
            fit_global_idx = pos_idx

        # Recompute predictions after any adoption
        exp_data_vals = A * np.exp(B * x_arr)
        # absolute RMSE on fitted points (use fit_global_idx)
        y_fit_used = values[fit_global_idx]
        pred_used = exp_data_vals[fit_global_idx]
        rmse_exp = float(np.sqrt(np.mean((pred_used - y_fit_used) ** 2)))
        # relative/log RMSE on fitted points (use log differences)
        try:
            rmse_log = float(np.sqrt(np.mean((np.log(pred_used) - np.log(y_fit_used)) ** 2)))
        except Exception:
            rmse_log = float('nan')

        # multiplicative factor corresponding to log-RMSE (exp(rmse_log)) for easier interpretation
        try:
            rmse_factor = float(np.exp(rmse_log))
        except Exception:
            rmse_factor = float('nan')

        print(f'exp fit (data) method={method}: A={A}, B={B}, rmse={rmse_exp}, rmse_log={rmse_log}, factor={rmse_factor}')
        exp_data_plot = np.where(exp_data_vals <= max_plot, exp_data_vals, np.nan)
        ax.plot(x_arr, exp_data_plot, color='C3', linewidth=2,
                label=f'exp fit (data) rmse={rmse_exp:.1f} log_rmse={rmse_log:.3f} (x{rmse_factor:.1f}) {method}')

    # Add power-series (polynomial) fit to the bar data with automatic degree selection
    # Try degrees from 1..max_deg and pick the one with smallest log-RMSE by fitting in log-space
    values_arr = values.to_numpy(dtype=float)
    finite_mask = np.isfinite(values_arr)
    n = finite_mask.sum()
    if n >= 2:
        max_deg = min(6, n - 1)
        x_poly = x_arr[finite_mask]
        y_poly = values_arr[finite_mask]
        # only use positive y for log-fit
        pos_mask_poly = y_poly > 0
        x_poly_pos = x_poly[pos_mask_poly]
        y_poly_pos = y_poly[pos_mask_poly]
        best_deg = None
        best_log_rmse = float('inf')
        best_coeffs = None
        # need at least deg+1 points to fit
        if y_poly_pos.size >= 2:
            for deg in range(1, max_deg + 1):
                if y_poly_pos.size < deg + 1:
                    break
                coeffs = np.polyfit(x_poly_pos, np.log(y_poly_pos), deg)
                p_log = np.poly1d(coeffs)
                pred_log = p_log(x_poly_pos)
                log_rmse = float(np.sqrt(np.mean((pred_log - np.log(y_poly_pos)) ** 2)))
                if log_rmse < best_log_rmse:
                    best_log_rmse = log_rmse
                    best_deg = deg
                    best_coeffs = coeffs
        if best_deg is not None:
            # build poly in log-space and exponentiate for plotting
            p_log = np.poly1d(best_coeffs)
            poly_vals = np.exp(p_log(x_arr))
            poly_vals_plot = np.where(poly_vals <= max_plot, poly_vals, np.nan)
            # compute log-RMSE on the exact points used for fitting
            pred_on_fit_log = p_log(x_poly_pos)
            rmse_log_poly = float(np.sqrt(np.mean((pred_on_fit_log - np.log(y_poly_pos)) ** 2)))
            # Confirm selection method
            print(f'Polynomial degree chosen by log-RMSE: deg={best_deg}, log_rmse={rmse_log_poly:.6f}')
            try:
                rmse_factor_poly = float(np.exp(rmse_log_poly))
            except Exception:
                rmse_factor_poly = float('nan')
            # absolute RMSE on fit points (for info)
            pred_on_fit = np.exp(pred_on_fit_log)
            rmse_poly_abs = float(np.sqrt(np.mean((pred_on_fit - y_poly_pos) ** 2)))
            print(f'Chosen poly (log-space) deg={best_deg}, abs_rmse={rmse_poly_abs}, log_rmse={rmse_log_poly}, coeffs={best_coeffs}')
            ax.plot(x_arr, poly_vals_plot, color='C4', linewidth=2,
                    label=f'poly(log) deg {best_deg} rmse={rmse_poly_abs:.1f} log_rmse={rmse_log_poly:.3f} (x{rmse_factor_poly:.1f}) (data)')

    ax.set_xticks(x)
    # Replace x labels by their index, only show every 10th label
    tick_labels = [str(i) if (i % 10 == 0) else '' for i in x]
    ax.set_xticklabels(tick_labels, rotation=90, fontsize=8)
    ax.set_ylabel(df.columns[1] if len(df.columns) > 1 else 'Value')
    ax.set_title('Highway traffic - frequency')
    ax.legend()
    fig.tight_layout()

    # Output filename: append '_all' when include_zeros is True
    suffix = '_all' if include_zeros else ''
    out = csv_path.parent / f'highwayTrafficAnalysis{suffix}.png'
    fig.savefig(out, dpi=150)
    print(f'Saved plot to: {out}')


if __name__ == '__main__':
    main(True)
    main(False)
