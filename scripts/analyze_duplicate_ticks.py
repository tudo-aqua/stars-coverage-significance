"""
Analyze metric_failed_monitors for duplicate ticks under decreasing rounding precision.

Two ticks are considered "the same" when the ego vehicle's spatial relation to
its neighbours (relative bumper-to-bumper distances), the ego and neighbour
speeds, and the ego and neighbour accelerations are all equal. Absolute lane
positions and the target monitor columns are intentionally excluded — only the
relative/relational kinematic state is compared.

Since these values are stored as (32-bit) floats, exact equality rarely holds
even for scenes that are semantically identical. This script rounds the
compared columns to a decreasing number of decimal places (starting from a
high precision and going down by one decimal place at a time) and reports how
many duplicate ticks appear at each rounding level, so the right precision for
"same scene" can be chosen empirically.

Usage:
    python analyze_duplicate_ticks.py --parquet metric_failed_monitors.parquet
    python analyze_duplicate_ticks.py --uri postgresql://user:pass@host:5432/db

Dependencies:
    pip install polars
    pip install connectorx   # only needed for --uri
"""

import argparse
import sys

import polars as pl


_NEIGHBORS = [
    "front", "rear",
    "front_left", "front_right",
    "rear_left", "rear_right",
]

# Relative bumper-to-bumper distances to each neighbour (already relative to ego).
DIST_COLS = [f"surrounding_dist_{d}" for d in _NEIGHBORS]

# Ego speed plus each neighbour's speed.
SPEED_COLS = ["ego_speed_mps"] + [f"surrounding_{d}_speed_mps" for d in _NEIGHBORS]

# Ego acceleration plus each neighbour's acceleration.
ACCEL_COLS = ["ego_accel_mps2"] + [f"surrounding_{d}_accel_mps2" for d in _NEIGHBORS]

COMPARE_COLS = DIST_COLS + SPEED_COLS + ACCEL_COLS

# None = exact (unrounded) values; then one fewer decimal place per step.
PRECISION_LEVELS: list = [None, 6, 5, 4, 3, 2, 1, 0]


def load(args: argparse.Namespace) -> pl.DataFrame:
    if args.parquet:
        return pl.read_parquet(args.parquet, columns=COMPARE_COLS)

    cols_sql = ", ".join(f'"{c}"' for c in COMPARE_COLS)
    query = f"SELECT {cols_sql} FROM metric_failed_monitors"
    print(f"Reading {len(COMPARE_COLS)} columns from metric_failed_monitors ...")
    try:
        return pl.read_database_uri(
            query=query,
            uri=args.uri,
            engine="connectorx",
        )
    except Exception as exc:
        sys.exit(f"Database read failed: {exc}")


def analyze(df: pl.DataFrame) -> pl.DataFrame:
    n_rows = len(df)
    rows = []

    for decimals in PRECISION_LEVELS:
        if decimals is None:
            rounded = df
            label = "exact"
        else:
            rounded = df.with_columns(
                [pl.col(c).round(decimals) for c in COMPARE_COLS]
            )
            label = f"{decimals} decimals"

        group_sizes = rounded.group_by(COMPARE_COLS).len()
        n_groups = len(group_sizes)
        duplicate_groups = group_sizes.filter(pl.col("len") > 1)
        n_duplicate_rows = n_rows - n_groups
        n_rows_in_duplicate_groups = duplicate_groups["len"].sum() if len(duplicate_groups) else 0
        max_group_size = group_sizes["len"].max() if n_groups else 0

        rows.append({
            "precision": label,
            "distinct_ticks": n_groups,
            "duplicate_rows": n_duplicate_rows,
            "duplicate_pct": round(100 * n_duplicate_rows / n_rows, 3) if n_rows else 0.0,
            "rows_in_duplicate_groups": n_rows_in_duplicate_groups,
            "duplicate_groups": len(duplicate_groups),
            "max_group_size": max_group_size,
        })

    return pl.DataFrame(rows)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--parquet", metavar="PATH",
                        help="Path to a Parquet export of metric_failed_monitors")
    source.add_argument("--uri", metavar="POSTGRES_URI",
                        help="PostgreSQL connection URI: postgresql://user:pass@host:port/db")
    args = parser.parse_args()

    df = load(args)
    n_rows = len(df)
    print(f"Loaded {n_rows:,} ticks.")
    print(f"Comparing on {len(COMPARE_COLS)} columns:")
    print(f"  distances ({len(DIST_COLS)}): {DIST_COLS}")
    print(f"  speeds    ({len(SPEED_COLS)}): {SPEED_COLS}")
    print(f"  accel     ({len(ACCEL_COLS)}): {ACCEL_COLS}\n")

    result = analyze(df)
    print(result.to_pandas().to_string(index=False))


if __name__ == "__main__":
    main()
