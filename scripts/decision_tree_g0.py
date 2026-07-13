"""
Decision tree classifier for next_tick_monitor_g0_Accidents_failed.

Loads a Parquet file produced by export_parquet.py, automatically tunes tree
hyperparameters via Optuna + mutant-grouped cross-validation, fits a single
LightGBM tree, and prints the tree as indented text.

Usage:
    python decision_tree_g0.py <path-to-parquet> [options]

Hyperparameter search (runs automatically):
    --n-trials N    Optuna trials for hyperparameter search (default: 50)
    --cv-folds K    Cross-validation folds, grouped by mutant ID (default: 5)

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
        # Migrate tables created before log_text / dot_source were added.
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS log_text TEXT")
        cur.execute("ALTER TABLE decision_tree_runs ADD COLUMN IF NOT EXISTS dot_source TEXT")
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
    conn.commit()


def _insert_run(
    conn,
    train_fraction: float,
    seed: int,
    train_mutants: set[int],
    test_mutants: set[int],
) -> int:
    """Insert a run record and its per-mutant trained_on flags; return the new run_id."""
    import psycopg2.extras

    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO decision_tree_runs "
            "  (train_fraction, seed, n_train_mutants, n_test_mutants) "
            "VALUES (%s, %s, %s, %s) RETURNING id",
            (train_fraction, seed, len(train_mutants), len(test_mutants)),
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
    group_ids: np.ndarray,
    n_trials: int,
    cv_folds: int,
    n_jobs: int,
) -> dict:
    """Search for the best tree hyperparameters using Optuna + mutant-grouped K-fold CV.

    Groups are mutant IDs so no mutant's rows appear in both the CV train and
    validation folds, matching the same leakage-prevention policy used for the
    final train/test split.

    Optimises ROC-AUC. Returns the best parameter dict found.
    """
    from sklearn.metrics import roc_auc_score
    from sklearn.model_selection import GroupKFold
    import optuna
    optuna.logging.set_verbosity(optuna.logging.WARNING)

    gkf = GroupKFold(n_splits=cv_folds)
    splits = list(gkf.split(X_train, y_train, groups=group_ids))

    def objective(trial: optuna.Trial) -> float:
        params = dict(
            num_leaves=trial.suggest_int("num_leaves", 4, 512, log=True),
            max_depth=trial.suggest_int("max_depth", 2, 20),
            min_child_samples=trial.suggest_int("min_child_samples", 20, 5000, log=True),
            min_split_gain=trial.suggest_float("min_split_gain", 0.0, 1.0),
        )
        scores = []
        for tr_idx, val_idx in splits:
            clf = lgb.LGBMClassifier(
                n_estimators=1,
                learning_rate=1.0,
                n_jobs=n_jobs,
                class_weight="balanced",
                random_state=0,
                verbose=-1,
                **params,
            )
            clf.fit(X_train.iloc[tr_idx], y_train[tr_idx])
            proba = clf.predict_proba(X_train.iloc[val_idx])[:, 1]
            try:
                scores.append(roc_auc_score(y_train[val_idx], proba))
            except ValueError:
                scores.append(0.5)
        return float(np.mean(scores))

    print(
        f"Tuning hyperparameters: {n_trials} Optuna trials, "
        f"{cv_folds}-fold mutant-grouped CV ..."
    )
    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=0),
    )
    study.optimize(objective, n_trials=n_trials, show_progress_bar=False)

    best = study.best_params
    print(f"  Best CV ROC-AUC : {study.best_value:.4f}")
    print(f"  Best params     : {best}\n")
    return best


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("parquet", help="Path to the Parquet export of metric_failed_monitors")
    parser.add_argument("--n-trials", type=int, default=50, metavar="N",
                        help="Optuna trials for automatic hyperparameter search (default: 50)")
    parser.add_argument("--cv-folds", type=int, default=5, metavar="K",
                        help="Cross-validation folds for hyperparameter search, grouped by mutant ID (default: 5)")
    parser.add_argument("--n-jobs", type=int, default=96, help="CPU threads for LightGBM (default: 96)")
    parser.add_argument("--train-fraction", type=float, default=1.0, metavar="F",
                        help="Fraction of rows used for training (0 < F <= 1.0, default: 1.0 = all data). "
                             "The held-out test split is what gets annotated and written to the DB.")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed for the train/test split (default: 42)")
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
            train_mutant_ids_for_cv = mutant_ids[train_mask]
            print(
                f"Mutants — train: {len(train_mutants):,}  |  test: {len(test_mutants):,}\n"
                f"Rows    — train: {train_mask.sum():,} ({y_train.mean():.1%} pos)"
                f"  |  all (annotated): {len(X):,}\n"
            )
        else:
            train_mutants = set(unique_mutants.tolist())
            test_mutants  = set()
            X_train, y_train = X, y
            train_mutant_ids_for_cv = mutant_ids

        # Predict on ALL rows so every row gets a leaf label.
        X_eval, y_eval, row_ids_eval = X, y, row_ids

        best_params = _tune_hyperparams(
            X_train, y_train,
            group_ids=train_mutant_ids_for_cv,
            n_trials=args.n_trials,
            cv_folds=args.cv_folds,
            n_jobs=args.n_jobs,
        )

        clf = lgb.LGBMClassifier(
            n_estimators=1,
            learning_rate=1.0,
            n_jobs=args.n_jobs,
            class_weight="balanced",
            random_state=0,
            verbose=-1,
            **best_params,
        )
        clf.fit(X_train, y_train)

        booster = clf.booster_
        model_info = booster.dump_model()
        num_leaves = model_info["tree_info"][0]["num_leaves"]

        raw_preds = booster.predict(X_train)
        train_acc = np.mean((raw_preds > 0.5).astype(int) == y_train)
        print(f"Leaves: {num_leaves}  |  Training accuracy: {train_acc:.4f}", end="")
        if args.train_fraction < 1.0:
            eval_preds = booster.predict(X_eval)
            eval_acc = np.mean((eval_preds > 0.5).astype(int) == y_eval)
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
                run_id = _insert_run(conn, args.train_fraction, args.seed,
                                     train_mutants, test_mutants)
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
