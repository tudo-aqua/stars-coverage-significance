"""
Decision tree classifier for next_tick_monitor_g0_Accidents_failed.

Loads a Parquet file produced by export_parquet.py, fits a single LightGBM
tree using all available cores, and prints the tree as indented text.

Usage:
    python decision_tree_g0.py <path-to-parquet> [--max-depth N] [--num-leaves N] [--n-jobs N] [--output tree.dot]

Dependencies:
    pip install polars lightgbm numpy
    pip install graphviz   # only needed for --output
"""

import argparse
import math
import sys

import lightgbm as lgb
import numpy as np
import polars as pl


FEATURE_COLS = [
    "monitor_g0_Accidents_failed",
    "monitor_g1_SafeDistanceToPrecedingVehicle_failed",
    "monitor_g2_emergencyBraking_failed",
    "monitor_g3_MaximumSpeedLimit_failed",
    "monitor_g4_TrafficFlow_failed",
    "monitor_i1_Stopping_failed",
    "monitor_i2_DrivingFasterThenLeftTraffic_failed",
    "ego_maneuver_speed",
    "ego_maneuver_lane_change",
    "surrounding_dist_front",
    "surrounding_dist_rear",
    "surrounding_dist_front_left",
    "surrounding_dist_front_right",
    "surrounding_dist_rear_left",
    "surrounding_dist_rear_right",
    "surrounding_dist_left",
    "surrounding_dist_right",
]

TARGET_COL = "next_tick_monitor_g0_Accidents_failed"
BOOL_COLS = [c for c in FEATURE_COLS if "failed" in c]


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


def load_and_prepare(path: str) -> tuple[np.ndarray, np.ndarray]:
    df = pl.read_parquet(path, columns=FEATURE_COLS + [TARGET_COL])

    missing = [c for c in FEATURE_COLS + [TARGET_COL] if c not in df.columns]
    if missing:
        sys.exit(f"Missing columns in Parquet file: {missing}")

    # Drop rows where target is null (last tick of a scenario has no next tick)
    df = df.filter(pl.col(TARGET_COL).is_not_null())

    # Encode lane-change enum as integer; null → -1
    df = df.with_columns(
        pl.col("ego_maneuver_lane_change")
        .cast(pl.Categorical)
        .to_physical()
        .fill_null(-1)
        .alias("ego_maneuver_lane_change")
    )

    # Cast boolean monitor columns to int8 — works for Bool dtype and "true"/"false" strings
    df = df.with_columns(
        [_bool_to_int8(c) for c in BOOL_COLS]
        + [_bool_to_int8(TARGET_COL)]
    )

    # -1 sentinel for missing distance/speed measurements
    X = df.select(FEATURE_COLS).fill_null(-1).to_numpy()
    y = df.select(TARGET_COL).to_numpy().ravel()

    return X, y


def _print_node(node: dict, feature_names: list[str], depth: int = 0) -> None:
    """Recursively print a LightGBM tree node in sklearn export_text style."""
    prefix = "|   " * depth

    if "split_feature" not in node:
        # Leaf: convert log-odds to probability via sigmoid
        prob = 1.0 / (1.0 + math.exp(-node["leaf_value"]))
        label = 1 if prob > 0.5 else 0
        print(f"{prefix}|--- class: {label}  (p={prob:.3f}, n={node['leaf_count']:,})")
        return

    fname = feature_names[node["split_feature"]]
    threshold = node["threshold"]
    decision = node.get("decision_type", "<=")

    # LightGBM represents boolean 0/1 splits as ~1e-35 floats; display as 0
    display_threshold = 0 if isinstance(threshold, float) and abs(threshold) < 1e-10 else threshold
    print(f"{prefix}|--- {fname} {decision} {display_threshold}")
    _print_node(node["left_child"], feature_names, depth + 1)
    print(f"{prefix}|--- {fname} > {display_threshold}")
    _print_node(node["right_child"], feature_names, depth + 1)


def print_tree(booster: lgb.Booster, feature_names: list[str]) -> None:
    model = booster.dump_model()
    _print_node(model["tree_info"][0]["tree_structure"], feature_names)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("parquet", help="Path to the Parquet export of metric_failed_monitors")
    parser.add_argument("--max-depth", type=int, default=None, help="Maximum tree depth (-1 = unlimited)")
    parser.add_argument("--num-leaves", type=int, default=31, help="Maximum number of leaves (default: 31)")
    parser.add_argument("--n-jobs", type=int, default=96, help="CPU threads for LightGBM (default: 96)")
    parser.add_argument("--output", default=None, help="Write Graphviz .dot file to this path")
    args = parser.parse_args()

    X, y = load_and_prepare(args.parquet)
    print(f"Loaded {len(X):,} rows  |  positives: {y.sum():,} ({y.mean():.1%})")

    clf = lgb.LGBMClassifier(
        n_estimators=1,
        learning_rate=1.0,          # no shrinkage for a single tree
        max_depth=args.max_depth if args.max_depth else -1,
        num_leaves=args.num_leaves,
        n_jobs=args.n_jobs,
        class_weight="balanced",    # compensates for the ~1.4% positive rate
        random_state=0,
        verbose=-1,
    )
    clf.fit(X, y, feature_name=FEATURE_COLS)

    booster = clf.booster_
    model_info = booster.dump_model()
    num_leaves = model_info["tree_info"][0]["num_leaves"]

    # Use the booster directly to avoid a sklearn feature-name warning on raw numpy input
    raw_preds = booster.predict(X)
    train_acc = np.mean((raw_preds > 0.5).astype(int) == y)
    print(f"Leaves: {num_leaves}  |  Training accuracy: {train_acc:.4f}\n")
    print_tree(booster, FEATURE_COLS)

    if args.output:
        try:
            graph = lgb.create_tree_digraph(
                booster,
                tree_index=0,
                show_info=["split_gain", "internal_count", "leaf_count"],
                precision=4,
                feature_names=FEATURE_COLS,
            )
            with open(args.output, "w") as f:
                f.write(graph.source)
            print(f"\nGraphviz dot file written to: {args.output}")
            print("Render with:  dot -Tpng tree.dot -o tree.png")
        except Exception as exc:
            print(f"Warning: could not write dot file ({exc}). Install graphviz: pip install graphviz")


if __name__ == "__main__":
    main()
