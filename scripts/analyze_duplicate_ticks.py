"""
Analyze metric_failed_monitors for duplicate ticks under decreasing rounding precision.

NOTE: on the full server-scale table, prefer the Kotlin reimplementation
(`RunAnalyzeDuplicateTicks.kt` / `./gradlew runAnalyzeDuplicateTicks`). This script was
observed getting silently killed on the server (no error, just terminated) — almost
certainly the OS OOM killer, since it materializes every group at every precision level
as a Python dict before writing JSON, multiplying per-row memory overhead by the number
of precision levels. It's still fine for smaller/local Parquet files.

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

Writes a JSON file (--json-output, default: duplicate_tick_groups.json) containing,
for every precision level, every group of ticks that share the same rounded values
(including groups of size 1), with the member row IDs and the rounded column values.

Dependencies:
    pip install polars
    pip install connectorx   # only needed for --uri
"""

import argparse
import json
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


ID_COL = "id"


def load(args: argparse.Namespace) -> pl.DataFrame:
    cols = [ID_COL] + COMPARE_COLS
    if args.parquet:
        return pl.read_parquet(args.parquet, columns=cols)

    cols_sql = ", ".join(f'"{c}"' for c in cols)
    query = f"SELECT {cols_sql} FROM metric_failed_monitors"
    print(f"Reading {len(cols)} columns from metric_failed_monitors ...")
    try:
        return pl.read_database_uri(
            query=query,
            uri=args.uri,
            engine="connectorx",
        )
    except Exception as exc:
        sys.exit(f"Database read failed: {exc}")


def analyze(df: pl.DataFrame) -> "tuple[pl.DataFrame, dict[str, list[dict]]]":
    n_rows = len(df)
    summary_rows = []
    groups_by_precision: dict[str, list[dict]] = {}

    for decimals in PRECISION_LEVELS:
        if decimals is None:
            rounded = df
            label = "exact"
        else:
            rounded = df.with_columns(
                [pl.col(c).round(decimals) for c in COMPARE_COLS]
            )
            label = f"{decimals} decimals"

        grouped = (
            rounded.group_by(COMPARE_COLS)
            .agg(pl.col(ID_COL).alias("row_ids"), pl.len().alias("count"))
            .sort("count", descending=True)
        )

        n_groups = len(grouped)
        duplicate_groups = grouped.filter(pl.col("count") > 1)
        n_duplicate_rows = n_rows - n_groups
        n_rows_in_duplicate_groups = duplicate_groups["count"].sum() if len(duplicate_groups) else 0
        max_group_size = grouped["count"].max() if n_groups else 0

        summary_rows.append({
            "precision": label,
            "distinct_ticks": n_groups,
            "duplicate_rows": n_duplicate_rows,
            "duplicate_pct": round(100 * n_duplicate_rows / n_rows, 3) if n_rows else 0.0,
            "rows_in_duplicate_groups": n_rows_in_duplicate_groups,
            "duplicate_groups": len(duplicate_groups),
            "max_group_size": max_group_size,
        })

        groups_by_precision[label] = grouped.to_dicts()

    return pl.DataFrame(summary_rows), groups_by_precision


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
    parser.add_argument("--json-output", default="duplicate_tick_groups.json", metavar="PATH",
                        help="Path to write the full group listing per precision level as JSON "
                             "(default: duplicate_tick_groups.json)")
    args = parser.parse_args()

    df = load(args)
    n_rows = len(df)
    print(f"Loaded {n_rows:,} ticks.")
    print(f"Comparing on {len(COMPARE_COLS)} columns:")
    print(f"  distances ({len(DIST_COLS)}): {DIST_COLS}")
    print(f"  speeds    ({len(SPEED_COLS)}): {SPEED_COLS}")
    print(f"  accel     ({len(ACCEL_COLS)}): {ACCEL_COLS}\n")

    result, groups_by_precision = analyze(df)
    print(result.to_pandas().to_string(index=False))

    with open(args.json_output, "w") as f:
        json.dump(groups_by_precision, f, indent=2)
    print(f"\nAll groups written to: {args.json_output}")


if __name__ == "__main__":
    main()
