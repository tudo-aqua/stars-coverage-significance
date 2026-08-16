"""
Export metric_failed_monitors from PostgreSQL to Parquet.

Uses connectorx to read the table in parallel partitions across all available
cores, then writes a snappy-compressed Parquet file that polars and the
decision-tree script can load in seconds.

Excludes `all_vehicles_json` by default: a per-row JSON array of every vehicle present at that
tick, added for the tick-replay feature. It's the single heaviest column in the table (everything
else is compact floats/ints/bools/short text) and isn't read by any current consumer of this
export (decision_tree_g0.py and analyze_duplicate_ticks.py both select specific named feature
columns). Pass --include-all-vehicles-json to include it anyway.

Usage:
    python export_parquet.py --uri postgresql://user:pass@host:5432/db
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --output out.parquet --partitions 96
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --include-all-vehicles-json

Dependencies:
    pip install polars connectorx psycopg2
"""

import argparse
import sys
from pathlib import Path

import polars as pl


EXCLUDED_COLUMNS_BY_DEFAULT = ["all_vehicles_json"]


def _build_query(uri: str, exclude: list[str], run_id: "int | None" = None) -> str:
    """Builds a SELECT of every metric_failed_monitors column except those in `exclude`.

    Column names are discovered at runtime via information_schema rather than hardcoded, so this
    doesn't need updating whenever the table schema changes.

    When `run_id` is given, also LEFT JOINs in that run's existing leaf assignment as a
    `leaf_node_id` column (NULL for rows not yet labeled under that run) — lets a downstream
    labeling pass identify "which ticks still need labeling for this run" from the Parquet file
    alone, without a live anti-join against the full metric_failed_monitors table.
    """
    import psycopg2

    conn = psycopg2.connect(uri)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_name = 'metric_failed_monitors' ORDER BY ordinal_position"
            )
            columns = [row[0] for row in cur.fetchall()]
    finally:
        conn.close()

    if not columns:
        sys.exit("Could not read column list for metric_failed_monitors — check --uri.")

    selected = [c for c in columns if c not in exclude]
    # Always double-quote: several columns (e.g. monitor_g0_Accidents_failed) have embedded
    # uppercase letters that Postgres would otherwise fold to lowercase.
    columns_sql = ", ".join(f'm."{c}"' for c in selected)

    if run_id is None:
        return f"SELECT {columns_sql} FROM metric_failed_monitors m"

    # run_id comes from argparse(type=int), so this is never an untrusted string.
    return (
        f"SELECT {columns_sql}, dtla.leaf_node_id "
        f"FROM metric_failed_monitors m "
        f"LEFT JOIN decision_tree_leaf_assignments dtla "
        f"  ON dtla.metric_failed_monitor_id = m.id AND dtla.run_id = {run_id}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--uri",
        required=True,
        help="PostgreSQL connection URI: postgresql://user:pass@host:port/db",
    )
    parser.add_argument(
        "--output",
        default="metric_failed_monitors.parquet",
        help="Output Parquet file path (default: metric_failed_monitors.parquet)",
    )
    parser.add_argument(
        "--partitions",
        type=int,
        default=96,
        help="Number of parallel read partitions matching available cores (default: 96)",
    )
    parser.add_argument(
        "--include-all-vehicles-json",
        action="store_true",
        help="Include the all_vehicles_json column (excluded by default; see module docstring)",
    )
    parser.add_argument(
        "--run-id",
        type=int,
        default=None,
        metavar="ID",
        help="decision_tree_runs.id to also include a 'leaf_node_id' column for (NULL where that "
             "run hasn't labeled a row yet) - lets a later labeling pass find not-yet-labeled "
             "rows from the Parquet file alone. Omit for a plain export (no extra column).",
    )
    args = parser.parse_args()

    exclude = [] if args.include_all_vehicles_json else EXCLUDED_COLUMNS_BY_DEFAULT
    query = _build_query(args.uri, exclude, run_id=args.run_id)

    print(f"Reading metric_failed_monitors with {args.partitions} parallel partitions ...")
    if exclude:
        print(f"  Excluding columns: {', '.join(exclude)}")
    if args.run_id is not None:
        print(f"  Including leaf_node_id for decision tree run {args.run_id}")
    try:
        # connectorx issues N parallel SELECT queries with non-overlapping WHERE
        # clauses on `tick`, then assembles the result as a zero-copy Arrow table.
        df = pl.read_database_uri(
            query=query,
            uri=args.uri,
            partition_on="tick",
            partition_num=args.partitions,
            engine="connectorx",
        )
    except Exception as exc:
        sys.exit(f"Database read failed: {exc}")

    output = Path(args.output)
    df.write_parquet(output, compression="snappy")

    size_mb = output.stat().st_size / 1_048_576
    print(f"Exported {len(df):,} rows → {output}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
