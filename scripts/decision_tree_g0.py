"""
Decision tree classifier for next_tick_monitor_g0_Accidents_failed.

Loads a Parquet file produced by export_parquet.py, automatically tunes tree
hyperparameters via Optuna (each trial fits and scores on the full training
set — no cross-validation split), fits a single LightGBM tree, and prints the
tree as indented text.

Usage:
    python decision_tree_g0.py <path-to-parquet> [options]

Hyperparameter search (runs automatically):
    --n-trials N      Optuna trials for hyperparameter search (default: 50)
    --tuning-jobs K   Concurrent trials during tuning; --n-jobs threads are
                      split across them (default: 8)
    --max-leaves N    Upper bound for num_leaves in the Optuna search (default: 512).
                      Lower values (e.g. 64) prevent tiny weighted-sample leaves when
                      class_weight='balanced' makes a single positive row look like 20+.
    --class-weight W  'balanced' (default) or 'scale-pos-weight'. With 'balanced' each
                      positive row is upweighted ~n_neg/n_pos, so min_child_samples
                      applies to weighted counts and tiny leaves can appear. With
                      'scale-pos-weight' LightGBM applies a scalar loss correction
                      instead, so min_child_samples applies to raw sample counts.

Feature groups (all enabled by default, disable with --no-<group>):
    --ego-maneuver        Ego maneuver: speed, lane change (2 cols)
    --ego-speed           Ego speed (1 col)
    --ego-accel           Ego acceleration (1 col)
    --ego-position        Ego front/back bumper lane position (2 cols)
    --distances           Bumper-to-bumper distances per grid cell (6 cols)
    --neighbor-kinematics Per-neighbour speed, accel, position, diffs (48 cols)
    --time-gaps           Per-neighbour TTC and time gap (16 cols)

Dependencies:
    pip install polars lightgbm numpy pandas optuna scikit-learn
    pip install graphviz   # only needed for --output
    pip install psycopg2   # only needed for --uri
"""

import argparse
import contextlib
import io
import math
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl


_NEIGHBORS = [
    "front", "rear",
    "front_left", "front_right",
    "rear_left", "rear_right",
]

FEATURE_GROUPS: dict[str, list[str]] = {
    "ego-maneuver": [
        "ego_maneuver_speed",
        "ego_maneuver_lane_change",
    ],
    "ego-speed": [
        "ego_speed_mps",
    ],
    "ego-accel": [
        "ego_accel_mps2",
    ],
    "ego-position": [
        "ego_front_bumper_pos_meters",
        "ego_back_bumper_pos_meters",
    ],
    "distances": [f"surrounding_dist_{d}" for d in _NEIGHBORS],
    "neighbor-kinematics": [
        f"surrounding_{d}_{attr}"
        for d in _NEIGHBORS
        for attr in [
            "speed_mps",
            "front_bumper_pos_meters",
            "back_bumper_pos_meters",
            "accel_mps2",
            "speed_diff_mps",
            "accel_diff_mps2",
        ]
    ],
    "time-gaps": [
        f"surrounding_{d}_{attr}"
        for d in _NEIGHBORS
        for attr in ["ttc_s", "tg_s"]
    ],
}

TARGET_COL = "next_tick_monitor_g0_Accidents_failed"

LANE_CHANGE_CATEGORIES = ["NO_LANE_CHANGE", "CHANGE_LEFT", "CHANGE_RIGHT"]


def _bool_to_int8(col_name: str) -> pl.Expr:
    """Convert a boolean-like column to Int8, regardless of whether it is stored
    as pl.Boolean or as 'true'/'false' strings (e.g. from a CSV-derived Parquet)."""
    return (
        pl.col(col_name)
        .cast(pl.String)
        .str.to_lowercase()
        .replace({"true": "1", "false": "0"})
        .cast(pl.Int8)
        .alias(col_name)
    )


def load_and_prepare(
    path: str, feature_cols: list[str]
) -> tuple[pd.DataFrame, np.ndarray, np.ndarray, np.ndarray]:
    """Returns (X, y, row_ids, mutant_ids) where row_ids are the DB primary keys."""
    df = pl.read_parquet(path, columns=["id", "mutant_id"] + feature_cols + [TARGET_COL])

    missing = [c for c in feature_cols + [TARGET_COL] if c not in df.columns]
    if missing:
        sys.exit(f"Missing columns in Parquet file: {missing}")

    df = df.filter(pl.col(TARGET_COL).is_not_null())

    bool_cols = [c for c in feature_cols if "failed" in c]
    df = df.with_columns(
        [_bool_to_int8(c) for c in bool_cols]
        + [_bool_to_int8(TARGET_COL)]
    )

    # Nulls in continuous columns (no vehicle in that grid cell) are left as NaN
    # so LightGBM can route them to the optimal branch natively.
    pdf = df.to_pandas()
    if "ego_maneuver_lane_change" in feature_cols:
        pdf["ego_maneuver_lane_change"] = pd.Categorical(
            pdf["ego_maneuver_lane_change"], categories=LANE_CHANGE_CATEGORIES
        )

    row_ids = pdf["id"].to_numpy()
    mutant_ids = pdf["mutant_id"].to_numpy()
    X = pdf[feature_cols]
    y = pdf[TARGET_COL].to_numpy()

    return X, y, row_ids, mutant_ids


def _print_node(node: dict, feature_names: list[str], depth: int = 0) -> None:
    """Recursively print a LightGBM tree node in sklearn export_text style."""
    prefix = "|   " * depth

    if "split_feature" not in node:
        prob = 1.0 / (1.0 + math.exp(-node["leaf_value"]))
        label = 1 if prob > 0.5 else 0
        print(f"{prefix}|--- class: {label}  (p={prob:.3f}, n={node['leaf_count']:,})")
        return

    fname = feature_names[node["split_feature"]]
    threshold = node["threshold"]
    decision = node.get("decision_type", "<=")

    if decision == "==" and fname == "ego_maneuver_lane_change":
        idx = int(threshold)
        left_cat = LANE_CHANGE_CATEGORIES[idx] if idx < len(LANE_CHANGE_CATEGORIES) else str(idx)
        right_cats = [c for i, c in enumerate(LANE_CHANGE_CATEGORIES) if i != idx]
        print(f"{prefix}|--- {fname} = {left_cat}")
        _print_node(node["left_child"], feature_names, depth + 1)
        print(f"{prefix}|--- {fname} in {{{', '.join(right_cats)}}}")
        _print_node(node["right_child"], feature_names, depth + 1)
        return

    # LightGBM represents boolean 0/1 splits as ~1e-35 floats; display as 0
    display_threshold = 0 if isinstance(threshold, float) and abs(threshold) < 1e-10 else threshold
    print(f"{prefix}|--- {fname} {decision} {display_threshold}")
    _print_node(node["left_child"], feature_names, depth + 1)
    print(f"{prefix}|--- {fname} > {display_threshold}")
    _print_node(node["right_child"], feature_names, depth + 1)


def _decode_dot_lane_change(dot_source: str) -> str:
    """Replace integer category codes in the dot output with enum names."""
    import re
    def replace(m: re.Match) -> str:
        idx = int(m.group(1))
        name = LANE_CHANGE_CATEGORIES[idx] if idx < len(LANE_CHANGE_CATEGORIES) else str(idx)
        return f"=<B>{name}</B>"
    return re.sub(
        r'(?<=<B>ego_maneuver_lane_change</B> )=<B>(\d+)</B>',
        replace,
        dot_source,
    )


def _replace_leaf_labels(dot_source: str, leaf_stats: dict[int, dict]) -> str:
    """Replace LightGBM leaf labels with majority class and accident/no-accident counts.

    Works line-by-line to avoid regex issues with <br/> inside graphviz HTML labels.
    """
    import re
    lines = dot_source.split("\n")
    result = []
    for line in lines:
        m = re.match(r"^(\s*)(leaf(\d+))\s*\[label=", line)
        if m:
            leaf_id = int(m.group(3))
            stats = leaf_stats.get(leaf_id, {})
            n_acc = stats.get("n_accidents", 0)
            n_no_acc = stats.get("n_no_accidents", 0)
            p = stats.get("p", float("nan"))
            cls = "accident" if n_acc > n_no_acc else "no-accident"
            label = (
                f"<leaf {leaf_id}: {cls} (p={p:.3f})<br/>"
                f"accidents: {n_acc:,}<br/>"
                f"no-accidents: {n_no_acc:,}>"
            )
            result.append(f"{m.group(1)}{m.group(2)} [label={label}] ;")
        else:
            result.append(line)
    return "\n".join(result)


def print_tree(booster: lgb.Booster, feature_names: list[str]) -> None:
    model = booster.dump_model()
    _print_node(model["tree_info"][0]["tree_structure"], feature_names)


def _actual_tree_depth(node: dict) -> int:
    """Computes the tree's actual max depth (root = 0) by walking the structure.

    LightGBM's `num_leaves`/`max_depth` hyperparameters are upper bounds for the
    tuner, not guarantees — leaf-wise growth can stop early (e.g. due to
    `min_child_samples`/`min_split_gain`), so the fitted tree's real leaf count
    and depth must be read back from the model rather than assumed to match the
    tuned values.
    """
    if "split_feature" not in node:
        return 0
    return 1 + max(
        _actual_tree_depth(node["left_child"]), _actual_tree_depth(node["right_child"])
    )


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
    (run_id, metric_failed_monitor_id, leaf_node_id) rows. psycopg2 releases the
    GIL during network I/O so threads provide real parallelism.
    ON CONFLICT DO NOTHING makes re-runs safe.
    """
    import math
    from concurrent.futures import ThreadPoolExecutor, as_completed

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


@contextlib.contextmanager
def _capture_stdout():
    """Tee sys.stdout to a StringIO buffer; yield the buffer."""
    buf = io.StringIO()
    real = sys.stdout

    class _Tee:
        def write(self, data: str) -> int:
            real.write(data)
            buf.write(data)
            return len(data)

        def flush(self) -> None:
            real.flush()

        def isatty(self) -> bool:
            return getattr(real, "isatty", lambda: False)()

    sys.stdout = _Tee()
    try:
        yield buf
    finally:
        sys.stdout = real


def _generate_dot(booster: lgb.Booster, leaf_stats_dict: dict) -> "str | None":
    """Build the enriched Graphviz DOT source for the single-tree model.

    Returns the DOT string, or None if graphviz is not installed.
    """
    try:
        graph = lgb.create_tree_digraph(
            booster,
            tree_index=0,
            show_info=["split_gain", "internal_count"],
            precision=4,
        )
        dot = _decode_dot_lane_change(graph.source)
        return _replace_leaf_labels(dot, leaf_stats_dict)
    except Exception as exc:
        print(f"Warning: could not generate DOT ({exc}). Install graphviz: pip install graphviz")
        return None


def _update_run_artifacts(
    uri: str, run_id: int, log_text: str, dot_source: "str | None"
) -> None:
    """Persist log text and DOT source back to the decision_tree_runs row."""
    import psycopg2

    conn = psycopg2.connect(uri)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE decision_tree_runs"
                " SET log_text = %s, dot_source = %s"
                " WHERE id = %s",
                (log_text, dot_source, run_id),
            )
        conn.commit()
    finally:
        conn.close()


def _resolve_mutant_numbers(uri: str, mutant_numbers: "set[int]") -> "dict[int, int]":
    """Looks up {mutant_number: mutant_id} for the given mutant_numbers via the `mutants` table.

    Numbers with no matching row are simply absent from the returned dict — the caller is
    responsible for warning about those.
    """
    import psycopg2

    conn = psycopg2.connect(uri)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT mutant_number, id FROM mutants WHERE mutant_number = ANY(%s)",
                (list(mutant_numbers),),
            )
            return dict(cur.fetchall())
    finally:
        conn.close()


def _ensure_tracking_tables(conn) -> None:
    """Create or migrate decision tree tracking tables."""
    with conn.cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS decision_tree_runs (
                id               SERIAL PRIMARY KEY,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                train_fraction   DOUBLE PRECISION NOT NULL,
                seed             INT NOT NULL,
                n_train_mutants  INT NOT NULL,
                n_test_mutants   INT NOT NULL,
                log_text         TEXT,
                dot_source       TEXT
            )
        """)
        # Migrate: columns added after initial schema
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS log_text TEXT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS dot_source TEXT")
        # Feature group flags
        for col in (
            "feat_ego_maneuver", "feat_ego_speed", "feat_ego_accel", "feat_ego_position",
            "feat_distances", "feat_neighbor_kinematics", "feat_time_gaps",
        ):
            cur.execute(f"ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS {col} BOOL")
        # Tuning configuration
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS n_trials INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS max_leaves_bound INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS class_weight TEXT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS scale_pos_weight DOUBLE PRECISION")
        # Best hyperparameters
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS hp_num_leaves INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS hp_max_depth INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS hp_min_child_samples INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS hp_min_split_gain DOUBLE PRECISION")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS tuning_roc_auc DOUBLE PRECISION")
        # Actually learned values of the fitted tree (may differ from the tuned
        # hyperparameters above, since num_leaves/max_depth are upper bounds only)
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS learned_num_leaves INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS learned_max_depth INT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS train_accuracy DOUBLE PRECISION")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS test_accuracy DOUBLE PRECISION")
        # Every mutant ID that went into this run (train ∪ test — i.e. everything
        # --mutant-ids/--mutant-numbers restricted the run to, or every mutant in the Parquet
        # file if neither was given). Mirrors decision_tree_mutant_splits for this run_id, but
        # as a single queryable column
        # instead of requiring a join/aggregate.
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS used_mutants INT[]")
        cur.execute("""
            CREATE TABLE IF NOT EXISTS decision_tree_mutant_splits (
                run_id     INT  NOT NULL REFERENCES decision_tree_runs(id) ON DELETE CASCADE,
                mutant_id  INT  NOT NULL,
                trained_on BOOL NOT NULL,
                PRIMARY KEY (run_id, mutant_id)
            )
        """)
        cur.execute("""
            CREATE TABLE IF NOT EXISTS decision_tree_leaf_assignments (
                run_id                   INT NOT NULL REFERENCES decision_tree_runs(id) ON DELETE CASCADE,
                metric_failed_monitor_id INT NOT NULL REFERENCES metric_failed_monitors(id) ON DELETE CASCADE,
                leaf_node_id             INT NOT NULL,
                PRIMARY KEY (run_id, metric_failed_monitor_id)
            )
        """)
        # metric_failed_monitor_id is only the trailing column of the primary key above, which
        # can't serve an efficient "find rows referencing this id" lookup. Without a leading index,
        # every row deleted from metric_failed_monitors forces a full scan of this table to check
        # its ON DELETE CASCADE, turning bulk deletes into an O(N*M) operation.
        cur.execute("""
            CREATE INDEX IF NOT EXISTS idx_decision_tree_leaf_assignments_metric_failed_monitor_id
                ON decision_tree_leaf_assignments (metric_failed_monitor_id)
        """)
    conn.commit()


def _insert_run(
    conn,
    train_fraction: float,
    seed: int,
    train_mutants: set[int],
    test_mutants: set[int],
    feature_flags: dict[str, bool],
    n_trials: int,
    max_leaves_bound: int,
    class_weight: str,
    scale_pos_weight: "float | None",
    best_params: dict,
    tuning_roc_auc: float,
    learned_num_leaves: int,
    learned_max_depth: int,
    train_accuracy: float,
    test_accuracy: "float | None",
) -> int:
    """Insert a run record and its per-mutant trained_on flags; return the new run_id."""
    import psycopg2.extras

    # Every mutant this run actually saw, train or test — what --mutant-ids/--mutant-numbers
    # restricted the run to, or every mutant in the Parquet file if neither was given.
    used_mutants = sorted(train_mutants | test_mutants)

    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO decision_tree_runs ("
            "  train_fraction, seed, n_train_mutants, n_test_mutants,"
            "  feat_ego_maneuver, feat_ego_speed, feat_ego_accel, feat_ego_position,"
            "  feat_distances, feat_neighbor_kinematics, feat_time_gaps,"
            "  n_trials, max_leaves_bound, class_weight, scale_pos_weight,"
            "  hp_num_leaves, hp_max_depth, hp_min_child_samples, hp_min_split_gain,"
            "  tuning_roc_auc,"
            "  learned_num_leaves, learned_max_depth, train_accuracy, test_accuracy,"
            "  used_mutants"
            ") VALUES ("
            "  %s, %s, %s, %s,"
            "  %s, %s, %s, %s,"
            "  %s, %s, %s,"
            "  %s, %s, %s, %s,"
            "  %s, %s, %s, %s,"
            "  %s,"
            "  %s, %s, %s, %s,"
            "  %s"
            ") RETURNING id",
            (
                train_fraction, seed, len(train_mutants), len(test_mutants),
                feature_flags["ego-maneuver"], feature_flags["ego-speed"],
                feature_flags["ego-accel"], feature_flags["ego-position"],
                feature_flags["distances"], feature_flags["neighbor-kinematics"],
                feature_flags["time-gaps"],
                n_trials, max_leaves_bound, class_weight, scale_pos_weight,
                best_params["num_leaves"], best_params["max_depth"],
                best_params["min_child_samples"], best_params["min_split_gain"],
                tuning_roc_auc,
                learned_num_leaves, learned_max_depth, train_accuracy, test_accuracy,
                used_mutants,
            ),
        )
        run_id = cur.fetchone()[0]
        records = (
            [(run_id, m, True)  for m in sorted(train_mutants)] +
            [(run_id, m, False) for m in sorted(test_mutants)]
        )
        psycopg2.extras.execute_values(
            cur,
            "INSERT INTO decision_tree_mutant_splits (run_id, mutant_id, trained_on) "
            "VALUES %s",
            records,
            page_size=10_000,
        )
    conn.commit()
    return run_id


def _tune_hyperparams(
    X_train: pd.DataFrame,
    y_train: np.ndarray,
    n_trials: int,
    n_jobs: int,
    tuning_jobs: int,
    max_leaves: int,
    lgb_weight_kwargs: dict,
) -> "tuple[dict, float]":
    """Search for the best tree hyperparameters using Optuna.

    Each trial fits on the entire training set (no held-out split) and is
    scored by its in-sample ROC-AUC, so hyperparameter search always learns
    from all available rows rather than a fraction held back for validation.

    `n_jobs` total CPU threads are split between `tuning_jobs` concurrent
    trials (run in parallel via Optuna's thread-based executor — LightGBM's
    fit releases the GIL, so this uses real cores) and the threads LightGBM
    uses per single tree fit within each trial. Since every trial fits only
    one tree, a lone fit does not scale near-linearly with more threads, so
    favouring more concurrent trials over more threads per trial is usually
    faster overall.

    Returns (best_params, best_roc_auc).
    """
    from sklearn.metrics import roc_auc_score
    import optuna
    from tqdm import tqdm
    optuna.logging.set_verbosity(optuna.logging.WARNING)

    threads_per_trial = max(1, n_jobs // tuning_jobs)

    def objective(trial: optuna.Trial) -> float:
        params = dict(
            num_leaves=trial.suggest_int("num_leaves", 4, max_leaves, log=True),
            max_depth=trial.suggest_int("max_depth", 2, 20),
            min_child_samples=trial.suggest_int("min_child_samples", 20, 5000, log=True),
            min_split_gain=trial.suggest_float("min_split_gain", 0.0, 1.0),
        )
        clf = lgb.LGBMClassifier(
            n_estimators=1,
            learning_rate=1.0,
            n_jobs=threads_per_trial,
            random_state=0,
            verbose=-1,
            **lgb_weight_kwargs,
            **params,
        )
        clf.fit(X_train, y_train)
        proba = clf.predict_proba(X_train)[:, 1]
        score = roc_auc_score(y_train, proba)
        return 0.5 if np.isnan(score) else float(score)

    print(
        f"Tuning hyperparameters: {n_trials} Optuna trials, scored on the full training set, "
        f"{tuning_jobs} concurrent trial(s) x {threads_per_trial} thread(s) each ..."
    )
    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=0),
    )

    # tqdm drives the bar's ETA/rate; the callback fires after every trial
    # (success or failure, from whichever worker thread finishes it) so the
    # bar never stalls on a failed trial. tqdm.update()/set_postfix() and
    # Optuna's study.best_value are safe to call concurrently.
    with tqdm(total=n_trials, desc="  Tuning", unit="trial", dynamic_ncols=True, ascii=True) as pbar:
        def _on_trial_end(study: "optuna.Study", trial: "optuna.trial.FrozenTrial") -> None:
            try:
                pbar.set_postfix(best_auc=f"{study.best_value:.4f}")
            except ValueError:
                pass  # no trial has completed successfully yet
            pbar.update(1)

        study.optimize(
            objective,
            n_trials=n_trials,
            n_jobs=tuning_jobs,
            show_progress_bar=False,
            callbacks=[_on_trial_end],
        )

    best = study.best_params
    best_roc_auc = study.best_value
    print(f"  Best training ROC-AUC : {best_roc_auc:.4f}")
    print(f"  Best params           : {best}\n")
    return best, best_roc_auc


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("parquet", help="Path to the Parquet export of metric_failed_monitors")
    parser.add_argument("--n-trials", type=int, default=50, metavar="N",
                        help="Optuna trials for automatic hyperparameter search (default: 50)")
    parser.add_argument("--max-leaves", type=int, default=512, metavar="N",
                        help="Upper bound for num_leaves in the Optuna search (default: 512). "
                             "Lower values (e.g. 64) reduce tiny leaves that appear when "
                             "--class-weight=balanced upweights each positive sample ~n_neg/n_pos.")
    parser.add_argument("--class-weight", default="balanced",
                        choices=["balanced", "scale-pos-weight"],
                        help="Class imbalance strategy (default: balanced). "
                             "'balanced' upweights each positive row so min_child_samples applies "
                             "to weighted counts; 'scale-pos-weight' applies a scalar loss correction "
                             "so min_child_samples applies to raw sample counts, preventing tiny leaves.")
    parser.add_argument("--n-jobs", type=int, default=48, help="CPU threads for LightGBM (default: 48)")
    parser.add_argument("--tuning-jobs", type=int, default=8, metavar="K",
                        help="Number of Optuna trials run concurrently during hyperparameter tuning "
                             "(default: 8). --n-jobs threads are split evenly across these K "
                             "concurrent trials (n-jobs / K threads per trial); since each trial "
                             "fits only one tree, more concurrent trials with fewer threads each is "
                             "usually faster than one trial at a time with many threads.")
    parser.add_argument("--train-fraction", type=float, default=1.0, metavar="F",
                        help="Fraction of rows used for training (0 < F <= 1.0, default: 1.0 = all data). "
                             "The held-out test split is what gets annotated and written to the DB.")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed for the train/test split (default: 42)")
    parser.add_argument("--mutant-ids", default=None, metavar="ID[,ID...]",
                        help="Comma-separated list of raw mutant_id values (metric_failed_monitors."
                             "mutant_id / mutants.id — the database's own serial primary key) to "
                             "restrict the run to (default: every mutant present in the Parquet "
                             "file). Applied before the --train-fraction/--seed split, so the split "
                             "itself is unaffected — it just runs over this smaller universe instead "
                             "of every mutant. Additive to everything else: combine with "
                             "--train-fraction for a train/test split within just these mutants, or "
                             "leave --train-fraction=1.0 to train on all of them. Combines with "
                             "--mutant-numbers (union) if both are given.")
    parser.add_argument("--mutant-numbers", default=None, metavar="N[,N...]",
                        help="Comma-separated list of mutant_number values (mutants.mutant_number — "
                             "the human-meaningful AutopilotMutant<N> index, e.g. the numbers in "
                             "AutopilotMutants.kt/README, NOT the same as mutant_id) to restrict the "
                             "run to. Requires --uri to resolve mutant_number -> mutant_id via the "
                             "mutants table, since the Parquet file only carries mutant_id. Otherwise "
                             "behaves exactly like --mutant-ids (applied before the split; combines "
                             "with --mutant-ids via union if both are given).")
    parser.add_argument("--output", default=None, metavar="PATH",
                        help="Write Graphviz .dot file to an explicit path (overrides --out-dir naming)")
    parser.add_argument("--annotate", default=None, metavar="PATH",
                        help="Write a Parquet file with a 'leaf_node_id' column added to every row")
    parser.add_argument("--uri", default=None, metavar="POSTGRES_URI",
                        help="Record run in the database and write leaf assignments (postgresql://user:pass@host:port/db)")
    parser.add_argument("--db-workers", type=int, default=48, metavar="N",
                        help="Parallel database connections used when writing leaf assignments (default: 48)")
    parser.add_argument("--out-dir", default=None, metavar="PATH",
                        help="Directory for run-named outputs: run_<id>.dot and run_<id>.log. "
                             "Defaults to the parquet file's directory when --uri is used.")

    # Feature group toggles — all enabled by default.
    # Monitor states are intentionally not a feature group: they are only ever
    # the prediction target (TARGET_COL), never an input, to avoid leaking the
    # current-tick value of the monitor being predicted one tick ahead.
    group_args = parser.add_argument_group("feature groups (all on by default)")
    group_args.add_argument(
        "--ego-maneuver", default=True, action=argparse.BooleanOptionalAction,
        help="Ego maneuver: planned speed, lane-change direction (2 cols)",
    )
    group_args.add_argument(
        "--ego-speed", default=True, action=argparse.BooleanOptionalAction,
        help="Ego speed: ego_speed_mps (1 col)",
    )
    group_args.add_argument(
        "--ego-accel", default=True, action=argparse.BooleanOptionalAction,
        help="Ego acceleration: ego_accel_mps2 (1 col)",
    )
    group_args.add_argument(
        "--ego-position", default=True, action=argparse.BooleanOptionalAction,
        help="Ego front/back bumper lane position (2 cols)",
    )
    group_args.add_argument(
        "--distances", default=True, action=argparse.BooleanOptionalAction,
        help="Bumper-to-bumper distances to nearest neighbour per grid cell (8 cols)",
    )
    group_args.add_argument(
        "--neighbor-kinematics", default=True, action=argparse.BooleanOptionalAction,
        help="Per-neighbour speed, accel, bumper positions, diffs (48 cols)",
    )
    group_args.add_argument(
        "--time-gaps", default=True, action=argparse.BooleanOptionalAction,
        help="Per-neighbour time-to-collision and time gap (16 cols)",
    )

    args = parser.parse_args()

    group_flags = {
        "ego-maneuver":        args.ego_maneuver,
        "ego-speed":           args.ego_speed,
        "ego-accel":           args.ego_accel,
        "ego-position":        args.ego_position,
        "distances":           args.distances,
        "neighbor-kinematics": args.neighbor_kinematics,
        "time-gaps":           args.time_gaps,
    }

    feature_cols = [
        col
        for group_name, cols in FEATURE_GROUPS.items()
        if group_flags[group_name]
        for col in cols
    ]

    if not feature_cols:
        sys.exit("No feature groups selected — enable at least one group.")

    enabled  = [g for g, on in group_flags.items() if on]
    disabled = [g for g, on in group_flags.items() if not on]

    # ── Capture stdout so the full run log can be stored in the database ──────
    run_id: "int | None" = None
    dot_source: "str | None" = None
    leaf_ids = None

    with _capture_stdout() as log_buf:
        print(f"Feature groups enabled:  {', '.join(enabled)}")
        if disabled:
            print(f"Feature groups disabled: {', '.join(disabled)}")
        print(f"Total feature columns:   {len(feature_cols)}\n")

        X, y, row_ids, mutant_ids = load_and_prepare(args.parquet, feature_cols)
        print(f"Loaded {len(X):,} rows  |  positives: {y.sum():,} ({y.mean():.1%})")

        # ── Optional mutant allow-list, applied before the split ──────────────
        # Restricts the universe the train/test split (below) operates over to just
        # these mutants, without changing how that split itself works.
        if args.mutant_ids or args.mutant_numbers:
            requested_mutants: "set[int]" = set()

            if args.mutant_ids:
                requested_mutants |= {
                    int(m.strip()) for m in args.mutant_ids.split(",") if m.strip()
                }

            if args.mutant_numbers:
                if not args.uri:
                    sys.exit(
                        "--mutant-numbers requires --uri to resolve mutant_number -> mutant_id "
                        "via the mutants table."
                    )
                requested_numbers = {
                    int(m.strip()) for m in args.mutant_numbers.split(",") if m.strip()
                }
                number_to_id = _resolve_mutant_numbers(args.uri, requested_numbers)
                missing_numbers = sorted(requested_numbers - number_to_id.keys())
                if missing_numbers:
                    print(f"Warning: {len(missing_numbers)} requested mutant_number(s) not found "
                          f"in the mutants table: {missing_numbers}")
                requested_mutants |= set(number_to_id.values())

            present_mutants = set(np.unique(mutant_ids).tolist())
            missing_mutants = sorted(requested_mutants - present_mutants)
            if missing_mutants:
                print(f"Warning: {len(missing_mutants)} requested mutant_id(s) not found in the "
                      f"Parquet data: {missing_mutants}")
            mutant_mask = np.isin(mutant_ids, list(requested_mutants))
            X, y, row_ids, mutant_ids = X[mutant_mask], y[mutant_mask], row_ids[mutant_mask], mutant_ids[mutant_mask]
            print(
                f"Restricted to {len(requested_mutants):,} requested mutant(s) "
                f"({len(requested_mutants & present_mutants):,} present): {len(X):,} rows remain.\n"
            )

        # ── Mutant-based train / test split ───────────────────────────────────
        # Split over unique mutant IDs so no mutant leaks across train/test.
        # All rows are annotated; trained_on is tracked per mutant in the DB.
        unique_mutants = np.unique(mutant_ids)
        if args.train_fraction < 1.0:
            rng = np.random.default_rng(args.seed)
            rng.shuffle(unique_mutants)
            n_train_mut = int(len(unique_mutants) * args.train_fraction)
            train_mutants = set(unique_mutants[:n_train_mut].tolist())
            test_mutants  = set(unique_mutants[n_train_mut:].tolist())

            train_mask = np.isin(mutant_ids, list(train_mutants))
            X_train, y_train = X.iloc[train_mask], y[train_mask]
            print(
                f"Mutants — train: {len(train_mutants):,}  |  test: {len(test_mutants):,}\n"
                f"Rows    — train: {train_mask.sum():,} ({y_train.mean():.1%} pos)"
                f"  |  all (annotated): {len(X):,}\n"
            )
        else:
            train_mutants = set(unique_mutants.tolist())
            test_mutants  = set()
            X_train, y_train = X, y

        # Predict on ALL rows so every row gets a leaf label.
        X_eval, y_eval, row_ids_eval = X, y, row_ids

        if args.class_weight == "balanced":
            lgb_weight_kwargs: dict = {"class_weight": "balanced"}
            print("Class weighting: balanced")
        else:
            n_pos = int(y_train.sum())
            n_neg = len(y_train) - n_pos
            spw = n_neg / n_pos
            lgb_weight_kwargs = {"scale_pos_weight": spw}
            print(f"Class weighting: scale_pos_weight = {spw:.1f}  (n_neg={n_neg:,} / n_pos={n_pos:,})")

        best_params, tuning_roc_auc = _tune_hyperparams(
            X_train, y_train,
            n_trials=args.n_trials,
            n_jobs=args.n_jobs,
            tuning_jobs=args.tuning_jobs,
            max_leaves=args.max_leaves,
            lgb_weight_kwargs=lgb_weight_kwargs,
        )

        clf = lgb.LGBMClassifier(
            n_estimators=1,
            learning_rate=1.0,
            n_jobs=args.n_jobs,
            random_state=0,
            verbose=-1,
            **lgb_weight_kwargs,
            **best_params,
        )
        clf.fit(X_train, y_train)

        booster = clf.booster_
        model_info = booster.dump_model()
        num_leaves = model_info["tree_info"][0]["num_leaves"]
        actual_max_depth = _actual_tree_depth(model_info["tree_info"][0]["tree_structure"])

        raw_preds = booster.predict(X_train)
        train_acc = float(np.mean((raw_preds > 0.5).astype(int) == y_train))
        eval_acc: "float | None" = None
        print(
            f"Leaves: {num_leaves} (tuned target: {best_params['num_leaves']})  |  "
            f"Depth: {actual_max_depth} (tuned bound: {best_params['max_depth']})  |  "
            f"Training accuracy: {train_acc:.4f}",
            end="",
        )
        if args.train_fraction < 1.0:
            eval_preds = booster.predict(X_eval)
            eval_acc = float(np.mean((eval_preds > 0.5).astype(int) == y_eval))
            print(f"  |  Test accuracy: {eval_acc:.4f}", end="")
        print("\n")

        feature_names = list(X.columns)
        gain  = booster.feature_importance(importance_type="gain")
        split = booster.feature_importance(importance_type="split")
        importance = (
            pl.DataFrame({
                "feature": feature_names,
                "gain":    gain.tolist(),
                "splits":  split.tolist(),
            })
            .filter(pl.col("splits") > 0)
            .sort("gain", descending=True)
        )
        print("Feature importances (used splits only, sorted by gain):")
        print(importance.to_pandas().to_string(index=False))
        print()

        print_tree(booster, feature_names)

        # ── Leaf IDs, per-leaf statistics, and DOT source ────────────────────
        leaf_ids = booster.predict(X_eval, pred_leaf=True)[:, 0].astype(int)

        leaf_p: dict[int, float] = {}
        def _collect_leaf_p(node: dict) -> None:
            if "split_feature" not in node:
                leaf_p[node["leaf_index"]] = 1.0 / (1.0 + math.exp(-node["leaf_value"]))
            else:
                _collect_leaf_p(node["left_child"])
                _collect_leaf_p(node["right_child"])
        _collect_leaf_p(model_info["tree_info"][0]["tree_structure"])

        split_label = "test split" if args.train_fraction < 1.0 else "all data"
        leaf_stats = (
            pl.DataFrame({"leaf_node_id": leaf_ids, "accident": y_eval.astype(int)})
            .group_by("leaf_node_id")
            .agg(
                pl.len().alias("n_rows"),
                pl.col("accident").sum().alias("n_accidents"),
                pl.col("accident").mean().alias("accident_rate"),
            )
            .with_columns(
                (pl.col("n_rows") - pl.col("n_accidents")).alias("n_no_accidents"),
                pl.col("leaf_node_id").map_elements(
                    lambda lid: leaf_p.get(lid, float("nan")), return_dtype=pl.Float64
                ).alias("p"),
            )
            .sort("leaf_node_id")
        )
        leaf_stats_dict: dict[int, dict] = {
            row["leaf_node_id"]: row for row in leaf_stats.to_dicts()
        }
        print(f"\nLeaf node summary ({len(leaf_stats)} leaves, {split_label}):")
        print(
            leaf_stats.select(["leaf_node_id", "n_rows", "n_accidents", "n_no_accidents", "accident_rate", "p"])
            .to_pandas()
            .to_string(index=False, float_format="{:.4f}".format)
        )

        # Generate DOT source; reused for file writes and DB storage.
        dot_source = _generate_dot(booster, leaf_stats_dict)

        # ── DB: insert run record (leaf assignments written after capture exits) ─
        if args.uri:
            import psycopg2
            conn = psycopg2.connect(args.uri)
            try:
                _ensure_tracking_tables(conn)
                run_id = _insert_run(
                    conn,
                    train_fraction=args.train_fraction,
                    seed=args.seed,
                    train_mutants=train_mutants,
                    test_mutants=test_mutants,
                    feature_flags=group_flags,
                    n_trials=args.n_trials,
                    max_leaves_bound=args.max_leaves,
                    class_weight=args.class_weight,
                    scale_pos_weight=lgb_weight_kwargs.get("scale_pos_weight"),
                    best_params=best_params,
                    tuning_roc_auc=tuning_roc_auc,
                    learned_num_leaves=num_leaves,
                    learned_max_depth=actual_max_depth,
                    train_accuracy=train_acc,
                    test_accuracy=eval_acc,
                )
            finally:
                conn.close()
            print(f"Run {run_id} recorded: {len(train_mutants):,} train mutants, "
                  f"{len(test_mutants):,} test mutants.")

    # ── Post-capture: persist artifacts and write files ───────────────────────
    # Runs before leaf insertion so artifacts land in DB/disk while that slow
    # write is still in progress.
    log_text = log_buf.getvalue()

    out_dir = Path(args.out_dir) if args.out_dir else Path(args.parquet).parent
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = f"run_{run_id}" if run_id is not None else Path(args.parquet).stem

    if dot_source is not None:
        dot_path = out_dir / f"{stem}.dot"
        dot_path.write_text(dot_source)
        print(f"DOT file written to: {dot_path}")

    log_path = out_dir / f"{stem}.log"
    log_path.write_text(log_text)
    print(f"Log written to: {log_path}")

    if run_id is not None:
        _update_run_artifacts(args.uri, run_id, log_text, dot_source)
        print(f"Run {run_id} artifacts saved to database.")

    # ── Leaf assignment insertion (slow — runs after artifacts are saved) ──────
    if args.uri and run_id is not None and leaf_ids is not None:
        _write_leaf_ids_to_db(args.uri, run_id, row_ids_eval, leaf_ids, workers=args.db_workers)

    # ── Explicit path overrides ────────────────────────────────────────────────
    if args.output:
        if dot_source is not None:
            with open(args.output, "w") as f:
                f.write(dot_source)
            print(f"\nGraphviz dot file written to: {args.output}")
            print("Render with:  dot -Tpng tree.dot -o tree.png")
        else:
            print("Warning: DOT source unavailable; --output file not written.")

    if args.annotate and leaf_ids is not None:
        annotated = X_eval.copy()
        annotated["id"] = row_ids_eval
        annotated[TARGET_COL] = y_eval
        annotated["leaf_node_id"] = leaf_ids
        pl.from_pandas(annotated).write_parquet(args.annotate)
        print(f"Annotated Parquet written to: {args.annotate}")


if __name__ == "__main__":
    main()
