"""
Step A.2a of docs/test-bed-plan.md: threshold sweep for the TTC and TG oracles (P5, P6).

Reads `scenario_mutant_outcomes` (built by sqlQueries/scenario_mutant_outcomes.sql) and, for every
oracle (TTC, TG), side (front, rear) and rear mode (always, after_lane_change), tries each threshold
of the grid. The threshold is chosen from the baseline (unmutated autopilot) alone: per oracle,
side and mode, the largest threshold where the baseline violates at most --max-pool-loss of the
scenarios. Those scenarios are removed from the oracle's pool (D9), so this bounds the pool loss.
The chosen front and rear thresholds are then combined (front OR rear) and the combined pool loss
is reported; it can exceed the limit, because the two sides can remove different scenarios.

Mutant numbers are only descriptive and do not enter the choice: killable mutants, mutants killed
in more than 90% of the valid scenarios, and the Jaccard overlap of the kill pairs with the G0
(accident) kill pairs. AiPilot is left out of the mutant numbers.

A violation is `min value < threshold`; NULL (no finite value during the run) never violates.

Usage (in the evaluation container on the server; connects to DB_URI below):
    python3 scripts/threshold_sweep.py
    python3 scripts/threshold_sweep.py --max-pool-loss 0.05 --output-dir postEvaluation/threshold_sweep

The table is read once and cached as Parquet in the output directory; delete the file (or pass
--refresh) after rebuilding the table.

Outputs in --output-dir:
    sweep.csv            one row per (oracle, side, mode, threshold), plus the combined rows
    chosen.json          chosen thresholds, ready to copy into the run config

Dependencies: polars, connectorx, numpy (see scripts/export_parquet.py).
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import polars as pl

# Same database as scripts/check_starting_scenarios.py.
DB_URI = "postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars"

GRID = {
    "ttc": [1.0, 1.5, 2.0, 2.5, 3.0, 4.0],
    "tg": [0.5, 0.7, 0.9, 1.0, 1.2, 1.5],
}

# (oracle, side, mode) -> column of scenario_mutant_outcomes.
COLUMNS = {
    ("ttc", "front", "always"): "min_front_ttc",
    ("ttc", "rear", "always"): "min_rear_ttc",
    ("ttc", "rear", "after_lane_change"): "min_rear_ttc_after_lc",
    ("tg", "front", "always"): "min_front_tg",
    ("tg", "rear", "always"): "min_rear_tg",
    ("tg", "rear", "after_lane_change"): "min_rear_tg_after_lc",
}

QUERY = """
SELECT o.scenario_config_id, o.mutant_id, o.accident_any, o.n_lane_changes,
       o.min_front_ttc, o.min_front_tg, o.min_rear_ttc, o.min_rear_tg,
       o.min_rear_ttc_after_lc, o.min_rear_tg_after_lc,
       mu.mutant_number = -1     AS is_baseline,
       mu.class_name = 'AiPilot' AS is_aipilot
FROM scenario_mutant_outcomes o
     JOIN mutants mu ON mu.id = o.mutant_id
"""


def load(uri: str, cache: Path, refresh: bool, partitions: int) -> pl.DataFrame:
    if cache.exists() and not refresh:
        print(f"Reading cached outcomes from {cache}")
        return pl.read_parquet(cache)
    print("Reading scenario_mutant_outcomes from the database ...")
    df = pl.read_database_uri(
        QUERY, uri, engine="connectorx", partition_on="scenario_config_id", partition_num=partitions
    )
    cache.parent.mkdir(parents=True, exist_ok=True)
    df.write_parquet(cache)
    return df


class Data:
    """Baseline vectors (per scenario) and mutant matrices (mutant x scenario)."""

    def __init__(self, df: pl.DataFrame):
        self.scenarios = np.sort(df["scenario_config_id"].unique().to_numpy())
        base = df.filter(pl.col("is_baseline"))
        muts = df.filter(~pl.col("is_baseline") & ~pl.col("is_aipilot"))
        self.mutants = np.sort(muts["mutant_id"].unique().to_numpy())

        missing_base = len(self.scenarios) - base.height
        missing_pairs = len(self.mutants) * len(self.scenarios) - muts.height
        if missing_base or missing_pairs:
            # Missing rows count as "no violation" below; say so instead of hiding it.
            print(f"WARNING: {missing_base} scenarios without a baseline row, "
                  f"{missing_pairs} missing (scenario, mutant) pairs")

        b_idx = np.searchsorted(self.scenarios, base["scenario_config_id"].to_numpy())
        m_row = np.searchsorted(self.mutants, muts["mutant_id"].to_numpy())
        m_col = np.searchsorted(self.scenarios, muts["scenario_config_id"].to_numpy())

        def vec(frame, col, idx, fill, dtype):
            out = np.full(len(self.scenarios), fill, dtype=dtype)
            out[idx] = frame[col].fill_null(fill).to_numpy()
            return out

        def mat(col, fill, dtype):
            out = np.full((len(self.mutants), len(self.scenarios)), fill, dtype=dtype)
            out[m_row, m_col] = muts[col].fill_null(fill).to_numpy()
            return out

        self.base_accident = vec(base, "accident_any", b_idx, False, bool)
        self.base_lane_changed = vec(base, "n_lane_changes", b_idx, 0, np.int64) > 0
        self.mut_accident = mat("accident_any", False, bool)
        self.base = {c: vec(base, c, b_idx, np.inf, np.float32) for c in COLUMNS.values()}
        self.mut = {c: mat(c, np.inf, np.float32) for c in COLUMNS.values()}
        self.g0_kills = self.mut_accident & ~self.base_accident[None, :]


def stats(d: Data, base_viol: np.ndarray, kills: np.ndarray) -> dict:
    n = len(d.scenarios)
    pool = int(n - base_viol.sum())
    per_mutant = kills.sum(axis=1)
    union = int((kills | d.g0_kills).sum())
    return {
        "baseline_violations": int(base_viol.sum()),
        "baseline_violations_with_lane_change": int((base_viol & d.base_lane_changed).sum()),
        "pool_loss": float(base_viol.sum() / n),
        "pool": pool,
        "killable_mutants": int((per_mutant > 0).sum()),
        "mutants_killed_in_over_90pct": int((per_mutant > 0.9 * pool).sum()),
        "kill_pairs": int(kills.sum()),
        "jaccard_with_g0": float((kills & d.g0_kills).sum() / union) if union else float("nan"),
    }


def evaluate(d: Data, thresholds: dict) -> dict:
    """Oracle over the given {column: threshold}, combined with OR; kill = mutant violates, baseline not."""
    base_viol = np.zeros(len(d.scenarios), dtype=bool)
    mut_viol = np.zeros((len(d.mutants), len(d.scenarios)), dtype=bool)
    for col, t in thresholds.items():
        base_viol |= d.base[col] < t
        mut_viol |= d.mut[col] < t
    return stats(d, base_viol, mut_viol & ~base_viol[None, :])


def self_check() -> None:
    """Two scenarios, one mutant: baseline violates only scenario 1, mutant only scenario 2."""
    df = pl.DataFrame({
        "scenario_config_id": [1, 2, 1, 2], "mutant_id": [0, 0, 5, 5],
        "accident_any": [False, False, False, True], "n_lane_changes": [1, 0, 0, 0],
        **{c: [0.5, None, None, 0.5] for c in COLUMNS.values()},
        "is_baseline": [True, True, False, False], "is_aipilot": [False] * 4,
    })
    d = Data(df)
    s = evaluate(d, {"min_front_ttc": 1.0})
    assert (s["baseline_violations"], s["baseline_violations_with_lane_change"], s["pool"]) == (1, 1, 1), s
    assert (s["killable_mutants"], s["kill_pairs"], s["jaccard_with_g0"]) == (1, 1, 1.0), s


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--uri", default=DB_URI)
    p.add_argument("--output-dir", type=Path, default=Path("postEvaluation/threshold_sweep"))
    p.add_argument("--max-pool-loss", type=float, default=0.05)
    p.add_argument("--partitions", type=int, default=32, help="parallel connectorx queries")
    p.add_argument("--refresh", action="store_true", help="ignore the Parquet cache")
    args = p.parse_args()

    self_check()
    t0 = time.perf_counter()
    df = load(args.uri, args.output_dir / "scenario_mutant_outcomes.parquet", args.refresh, args.partitions)
    t1 = time.perf_counter()
    d = Data(df)
    del df
    t2 = time.perf_counter()
    print(f"{len(d.scenarios)} scenarios, {len(d.mutants)} mutants (AiPilot excluded); "
          f"load {t1 - t0:.1f} s, matrices {t2 - t1:.1f} s")

    rows = []
    g0 = stats(d, d.base_accident, d.g0_kills)
    rows.append({"oracle": "g0", "side": "", "mode": "", "threshold": "", **g0})

    chosen = {}
    for (oracle, side, mode), col in COLUMNS.items():
        best = None
        for t in GRID[oracle]:
            s = evaluate(d, {col: t})
            rows.append({"oracle": oracle, "side": side, "mode": mode, "threshold": str(t), **s})
            if s["pool_loss"] <= args.max_pool_loss:
                best = t
        chosen[(oracle, side, mode)] = best

    combined = {}
    for oracle in GRID:
        front = chosen[(oracle, "front", "always")]
        for mode in ("always", "after_lane_change"):
            rear = chosen[(oracle, "rear", mode)]
            if front is None or rear is None:
                print(f"WARNING: no threshold of {oracle} stays within the pool-loss limit "
                      f"(front {front}, rear/{mode} {rear})")
                continue
            s = evaluate(d, {COLUMNS[(oracle, "front", "always")]: front,
                             COLUMNS[(oracle, "rear", mode)]: rear})
            rows.append({"oracle": oracle, "side": "front+rear", "mode": mode,
                         "threshold": f"{front}/{rear}", **s})
            combined[f"{oracle}_{mode}"] = {
                f"{oracle}_front": front, f"{oracle}_rear": rear, "rear_mode": mode,
                "combined_pool_loss": s["pool_loss"],
                "within_limit": s["pool_loss"] <= args.max_pool_loss,
            }

    t3 = time.perf_counter()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    table = pl.DataFrame(rows)
    table.write_csv(args.output_dir / "sweep.csv")
    (args.output_dir / "chosen.json").write_text(json.dumps({
        "max_pool_loss": args.max_pool_loss,
        "grid": GRID,
        "per_side": {"/".join(k): v for k, v in chosen.items()},
        "combined": combined,
        "seconds": {"load": t1 - t0, "matrices": t2 - t1, "sweep": t3 - t2},
    }, indent=2))

    with pl.Config(tbl_rows=-1, tbl_cols=-1, tbl_width_chars=250, float_precision=4):
        print(table)
    print(json.dumps(combined, indent=2))
    print(f"sweep {t3 - t2:.1f} s; wrote {args.output_dir / 'sweep.csv'} and chosen.json")


if __name__ == "__main__":
    main()
