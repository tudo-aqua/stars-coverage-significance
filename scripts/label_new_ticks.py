"""
Labels newly-produced metric_failed_monitors ticks with an already-trained decision tree,
without retraining.

Reads a Parquet export produced by `export_parquet.py --run-id <ID>` (which carries each row's
existing `leaf_node_id` for that run, NULL where not yet labeled), reloads that run's serialized
LightGBM booster from `decision_tree_runs.model_text`, predicts leaf ids for just the rows still
missing a `leaf_node_id`, and writes them into `decision_tree_leaf_assignments`.

Usage:
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --run-id 8 --output run_8.parquet
    python label_new_ticks.py run_8.parquet --run-id 8 --uri postgresql://user:pass@host:5432/db

Dependencies:
    pip install polars pandas lightgbm psycopg2
"""

import argparse
import math
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl

# Mirrors decision_tree_g0.py's LANE_CHANGE_CATEGORIES - must stay in sync, since the booster's
# categorical encoding for ego_maneuver_lane_change depends on this exact category order.
LANE_CHANGE_CATEGORIES = ["NO_LANE_CHANGE", "CHANGE_LEFT", "CHANGE_RIGHT"]


def _fetch_run(uri: str, run_id: int) -> tuple[str, list[str]]:
    """Returns (model_text, feature_columns) for `run_id`.

    Exits with an error if the run doesn't exist, or has no serialized model/feature_columns
    (e.g. it was trained before decision_tree_g0.py started recording those columns).
    """
    import psycopg2

    conn = psycopg2.connect(uri)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT model_text, feature_columns FROM decision_tree_runs WHERE id = %s",
                (run_id,),
            )
            row = cur.fetchone()
    finally:
        conn.close()

    if row is None:
        sys.exit(f"No decision_tree_runs row for id={run_id}.")
    model_text, feature_columns = row
    if model_text is None or feature_columns is None:
        sys.exit(
            f"decision_tree_runs.id={run_id} has no serialized model/feature_columns - it was "
            "likely trained before this was recorded and can't be reloaded for labeling."
        )
    return model_text, list(feature_columns)


def _load_unlabeled(path: str, feature_columns: list[str]) -> tuple[pd.DataFrame, np.ndarray]:
    """Returns (X, row_ids) for rows in `path` with a NULL leaf_node_id - i.e. ticks not yet
    labeled for this run - restricted to exactly the feature columns the booster was trained on,
    in that exact order. Booster.predict() aligns feature columns positionally, not by name, so
    the order here has to match feature_columns as returned by _fetch_run exactly.
    """
    df = pl.read_parquet(path, columns=["id", "leaf_node_id"] + feature_columns)

    missing = [c for c in feature_columns if c not in df.columns]
    if missing:
        sys.exit(f"Missing feature columns in Parquet file: {missing}")
    if "leaf_node_id" not in df.columns:
        sys.exit(
            "Parquet file has no leaf_node_id column - re-export it with "
            "'export_parquet.py --run-id <ID>' first."
        )

    df = df.filter(pl.col("leaf_node_id").is_null())

    # Nulls in continuous feature columns (no vehicle in that grid cell) are left as NaN, exactly
    # as at training time, so the booster routes them the same way (see decision_tree_g0.py).
    pdf = df.to_pandas()
    if "ego_maneuver_lane_change" in feature_columns:
        pdf["ego_maneuver_lane_change"] = pd.Categorical(
            pdf["ego_maneuver_lane_change"], categories=LANE_CHANGE_CATEGORIES
        )

    row_ids = pdf["id"].to_numpy()
    X = pdf[feature_columns]
    return X, row_ids


def _write_leaf_ids_to_db(
    uri: str,
    run_id: int,
    row_ids: np.ndarray,
    leaf_ids: np.ndarray,
    workers: int = 48,
    page_size: int = 100_000,
) -> None:
    """Insert leaf node assignments into decision_tree_leaf_assignments in parallel.

    Each worker opens its own connection and inserts a non-overlapping slice of
    (run_id, metric_failed_monitor_id, leaf_node_id) rows. psycopg2 releases the GIL during
    network I/O so threads provide real parallelism. ON CONFLICT DO NOTHING makes re-runs safe.

    Mirrors decision_tree_g0.py's helper of the same name (duplicated rather than imported -
    every script in this directory is self-contained; see its module docstring for deps).
    """
    import psycopg2
    import psycopg2.extras

    pairs = list(zip(row_ids.tolist(), leaf_ids.tolist()))
    n = len(pairs)

    slice_size = math.ceil(n / workers)
    slices = [pairs[i : i + slice_size] for i in range(0, n, slice_size)]

    completed = 0

    def _insert_slice(chunk: list) -> int:
        records = [(run_id, row_id, leaf) for row_id, leaf in chunk]
        c = psycopg2.connect(uri)
        try:
            with c.cursor() as cur:
                psycopg2.extras.execute_values(
                    cur,
                    "INSERT INTO decision_tree_leaf_assignments"
                    " (run_id, metric_failed_monitor_id, leaf_node_id)"
                    " VALUES %s ON CONFLICT DO NOTHING",
                    records,
                    page_size=page_size,
                )
            c.commit()
        finally:
            c.close()
        return len(chunk)

    print(f"\nInserting leaf assignments into DB (run {run_id}, {n:,} rows, {len(slices)} workers) ...")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(_insert_slice, s): s for s in slices}
        for fut in as_completed(futures):
            completed += fut.result()
            print(f"  {completed:,} / {n:,} rows inserted", end="\r", flush=True)

    print(f"\nDone. {completed:,} leaf assignments inserted for run {run_id}.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "parquet",
        help="Path to a Parquet export from 'export_parquet.py --run-id <ID>' "
             "(must include the leaf_node_id column for that same run)",
    )
    parser.add_argument(
        "--run-id", type=int, required=True, metavar="ID",
        help="decision_tree_runs.id whose serialized model should be used to label new ticks",
    )
    parser.add_argument(
        "--uri", required=True, metavar="POSTGRES_URI",
        help="PostgreSQL connection URI: postgresql://user:pass@host:port/db",
    )
    parser.add_argument(
        "--db-workers", type=int, default=48, metavar="N",
        help="Parallel database connections used when writing leaf assignments (default: 48)",
    )
    args = parser.parse_args()

    print(f"Loading run {args.run_id}'s serialized model from the database ...")
    model_text, feature_columns = _fetch_run(args.uri, args.run_id)
    booster = lgb.Booster(model_str=model_text)
    print(f"  {len(feature_columns)} feature columns, {booster.num_trees()} tree(s).")

    print(f"Loading not-yet-labeled ticks from {args.parquet} ...")
    X, row_ids = _load_unlabeled(args.parquet, feature_columns)
    print(f"  {len(row_ids):,} ticks not yet labeled for run {args.run_id}.")

    if len(row_ids) == 0:
        print("Nothing to label.")
        return

    print("Predicting leaf ids ...")
    leaf_ids = booster.predict(X, pred_leaf=True)[:, 0].astype(int)

    _write_leaf_ids_to_db(args.uri, args.run_id, row_ids, leaf_ids, workers=args.db_workers)


if __name__ == "__main__":
    main()
