"""
Export metric_failed_monitors from PostgreSQL to Parquet.

Reads the table in id-range chunks of --chunk-rows rows, each split into --partitions queries
that connectorx runs in parallel, and appends every chunk as row groups to one snappy-compressed
Parquet file. Only one chunk is held in memory at a time: the table has ~10^9 rows, and loading it
whole got the export OOM-killed. The file is written to `<output>.partial` and only renamed to
`<output>` once complete, so an aborted export never leaves a truncated file behind.

Excludes `all_vehicles_json_deflate` by default: a per-row JSON array of every vehicle present at
that tick, added for the tick-replay feature. It's the single heaviest column in the table
(everything else is compact floats/ints/bools/short text) and isn't read by any current consumer
of this export (decision_tree_g0.py and analyze_duplicate_ticks.py both select specific named
feature columns). Pass --include-all-vehicles-json to include it anyway. It is exported as the
compressed bytes stored in the database; decode one value with
`all_vehicles_json_codec.decompress(value)` (see all_vehicles_json_codec.py in this directory).

Usage:
    python export_parquet.py --uri postgresql://user:pass@host:5432/db
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --output out.parquet --partitions 96
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --chunk-rows 2000000
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --include-all-vehicles-json

Dependencies:
    pip install polars connectorx psycopg2 pyarrow
"""

import argparse
import sys
from pathlib import Path

import polars as pl
import pyarrow.parquet as pq


EXCLUDED_COLUMNS_BY_DEFAULT = ["all_vehicles_json_deflate"]


def _build_query(uri: str, exclude: list[str]) -> str:
    """Builds a SELECT of every metric_failed_monitors column except those in `exclude`.

    Column names are discovered at runtime via information_schema rather than hardcoded, so this
    doesn't need updating whenever the table schema changes.
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

    return f"SELECT {columns_sql} FROM metric_failed_monitors m"


def _read_id_bounds(uri: str) -> tuple[int, int]:
    """Returns the smallest and largest metric_failed_monitors id (answered from the primary key index)."""
    import psycopg2

    conn = psycopg2.connect(uri)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT min(id), max(id) FROM metric_failed_monitors")
            first_id, last_id = cur.fetchone()
    finally:
        conn.close()

    if first_id is None:
        sys.exit("metric_failed_monitors is empty - nothing to export.")
    return first_id, last_id


def _chunk_queries(query: str, lo: int, hi: int, partitions: int) -> list[str]:
    """Splits the id range [lo, hi) into up to `partitions` queries for connectorx to run in parallel.

    The ranges are spelled out as explicit WHERE clauses on the primary key rather than left to
    connectorx's partition_on, so every query is an index range scan and the ranges are exactly
    [lo, hi) regardless of how connectorx treats partition bounds.
    """
    step = -(-(hi - lo) // partitions)  # ceil division
    return [
        f"{query} WHERE m.id >= {start} AND m.id < {min(start + step, hi)}"
        for start in range(lo, hi, step)
    ]


def _read_leaf_assignments(uri: str, run_id: int) -> pl.DataFrame:
    """Reads run `run_id`'s leaf assignments as (id, leaf_node_id) rows.

    Read in one query and joined in polars rather than joined in SQL: the parallel partition
    queries of the main export would each have to expand all of the run's
    decision_tree_leaf_assignment_chunks again.
    """
    # run_id comes from argparse(type=int), so this is never an untrusted string.
    return pl.read_database_uri(
        query=(
            "SELECT metric_failed_monitor_id AS id, leaf_node_id "
            f"FROM decision_tree_leaf_assignments WHERE run_id = {run_id}"
        ),
        uri=uri,
        engine="connectorx",
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
        help="Number of parallel queries per chunk, matching available cores (default: 96)",
    )
    parser.add_argument(
        "--chunk-rows",
        type=int,
        default=10_000_000,
        metavar="N",
        help="Ids read (and held in memory) per chunk; lower this if the export still runs out of "
             "memory (default: 10000000)",
    )
    parser.add_argument(
        "--include-all-vehicles-json",
        action="store_true",
        help="Include the all_vehicles_json_deflate column (excluded by default; see module docstring)",
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
    query = _build_query(args.uri, exclude)
    first_id, last_id = _read_id_bounds(args.uri)

    print(
        f"Reading metric_failed_monitors ids {first_id:,}..{last_id:,} in chunks of "
        f"{args.chunk_rows:,} ids, {args.partitions} parallel queries each ..."
    )
    if exclude:
        print(f"  Excluding columns: {', '.join(exclude)}")

    leaf_assignments = None
    if args.run_id is not None:
        print(f"  Including leaf_node_id for decision tree run {args.run_id}")
        try:
            leaf_assignments = _read_leaf_assignments(args.uri, args.run_id)
        except Exception as exc:
            sys.exit(f"Database read failed: {exc}")

    output = Path(args.output)
    partial = output.with_name(output.name + ".partial")
    writer = None
    schema = None
    rows = 0
    try:
        for lo in range(first_id, last_id + 1, args.chunk_rows):
            hi = min(lo + args.chunk_rows, last_id + 1)
            try:
                df = pl.read_database_uri(
                    query=_chunk_queries(query, lo, hi, args.partitions),
                    uri=args.uri,
                    engine="connectorx",
                )
                if leaf_assignments is not None:
                    # Adds that run's leaf assignment as a `leaf_node_id` column (null for rows not
                    # yet labeled under that run), so a later labeling pass can find "which ticks
                    # still need labeling for this run" from the Parquet file alone.
                    df = df.join(leaf_assignments, on="id", how="left")
            except Exception as exc:
                sys.exit(f"Database read failed for ids [{lo}, {hi}): {exc}")

            if df.is_empty():
                continue
            table = df.to_arrow()
            if writer is None:
                schema = table.schema
                writer = pq.ParquetWriter(partial, schema, compression="snappy")
            writer.write_table(table.cast(schema))
            rows += len(df)
            print(f"  ids [{lo:,}, {hi:,}): {len(df):,} rows ({rows:,} total)", flush=True)
            # Drop this chunk before reading the next one, so at most one chunk is in memory.
            del df, table
    finally:
        if writer is not None:
            writer.close()

    partial.replace(output)
    size_mb = output.stat().st_size / 1_048_576
    print(f"Exported {rows:,} rows → {output}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
