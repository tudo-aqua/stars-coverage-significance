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
import pandas as pd
import polars as pl


FEATURE_COLS = [
    # Current-tick monitor states
    "monitor_g0_Accidents_failed",
    "monitor_g1_SafeDistanceToPrecedingVehicle_failed",
    "monitor_g2_emergencyBraking_failed",
    "monitor_g3_MaximumSpeedLimit_failed",
    "monitor_g4_TrafficFlow_failed",
    "monitor_i1_Stopping_failed",
    "monitor_i2_DrivingFasterThenLeftTraffic_failed",
    # Ego maneuver
    "ego_maneuver_speed",
    "ego_maneuver_lane_change",
    # Ego state
    "ego_speed_mps",
    "ego_accel_mps2",
    "ego_front_bumper_pos_meters",
    "ego_back_bumper_pos_meters",
    # Bumper-to-bumper distances to nearest neighbour per grid cell
    "surrounding_dist_front",
    "surrounding_dist_rear",
    "surrounding_dist_front_left",
    "surrounding_dist_front_right",
    "surrounding_dist_rear_left",
    "surrounding_dist_rear_right",
    "surrounding_dist_left",
    "surrounding_dist_right",
    # Front neighbour
    "surrounding_front_speed_mps",
    "surrounding_front_front_bumper_pos_meters",
    "surrounding_front_back_bumper_pos_meters",
    "surrounding_front_accel_mps2",
    "surrounding_front_speed_diff_mps",
    "surrounding_front_accel_diff_mps2",
    "surrounding_front_ttc_s",
    "surrounding_front_tg_s",
    # Rear neighbour
    "surrounding_rear_speed_mps",
    "surrounding_rear_front_bumper_pos_meters",
    "surrounding_rear_back_bumper_pos_meters",
    "surrounding_rear_accel_mps2",
    "surrounding_rear_speed_diff_mps",
    "surrounding_rear_accel_diff_mps2",
    "surrounding_rear_ttc_s",
    "surrounding_rear_tg_s",
    # Front-left neighbour
    "surrounding_front_left_speed_mps",
    "surrounding_front_left_front_bumper_pos_meters",
    "surrounding_front_left_back_bumper_pos_meters",
    "surrounding_front_left_accel_mps2",
    "surrounding_front_left_speed_diff_mps",
    "surrounding_front_left_accel_diff_mps2",
    "surrounding_front_left_ttc_s",
    "surrounding_front_left_tg_s",
    # Front-right neighbour
    "surrounding_front_right_speed_mps",
    "surrounding_front_right_front_bumper_pos_meters",
    "surrounding_front_right_back_bumper_pos_meters",
    "surrounding_front_right_accel_mps2",
    "surrounding_front_right_speed_diff_mps",
    "surrounding_front_right_accel_diff_mps2",
    "surrounding_front_right_ttc_s",
    "surrounding_front_right_tg_s",
    # Rear-left neighbour
    "surrounding_rear_left_speed_mps",
    "surrounding_rear_left_front_bumper_pos_meters",
    "surrounding_rear_left_back_bumper_pos_meters",
    "surrounding_rear_left_accel_mps2",
    "surrounding_rear_left_speed_diff_mps",
    "surrounding_rear_left_accel_diff_mps2",
    "surrounding_rear_left_ttc_s",
    "surrounding_rear_left_tg_s",
    # Rear-right neighbour
    "surrounding_rear_right_speed_mps",
    "surrounding_rear_right_front_bumper_pos_meters",
    "surrounding_rear_right_back_bumper_pos_meters",
    "surrounding_rear_right_accel_mps2",
    "surrounding_rear_right_speed_diff_mps",
    "surrounding_rear_right_accel_diff_mps2",
    "surrounding_rear_right_ttc_s",
    "surrounding_rear_right_tg_s",
    # Left neighbour
    "surrounding_left_speed_mps",
    "surrounding_left_front_bumper_pos_meters",
    "surrounding_left_back_bumper_pos_meters",
    "surrounding_left_accel_mps2",
    "surrounding_left_speed_diff_mps",
    "surrounding_left_accel_diff_mps2",
    "surrounding_left_ttc_s",
    "surrounding_left_tg_s",
    # Right neighbour
    "surrounding_right_speed_mps",
    "surrounding_right_front_bumper_pos_meters",
    "surrounding_right_back_bumper_pos_meters",
    "surrounding_right_accel_mps2",
    "surrounding_right_speed_diff_mps",
    "surrounding_right_accel_diff_mps2",
    "surrounding_right_ttc_s",
    "surrounding_right_tg_s",
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


LANE_CHANGE_CATEGORIES = ["NO_LANE_CHANGE", "CHANGE_LEFT", "CHANGE_RIGHT"]


def load_and_prepare(path: str) -> tuple[pd.DataFrame, np.ndarray]:
    df = pl.read_parquet(path, columns=FEATURE_COLS + [TARGET_COL])

    missing = [c for c in FEATURE_COLS + [TARGET_COL] if c not in df.columns]
    if missing:
        sys.exit(f"Missing columns in Parquet file: {missing}")

    # Drop rows where target is null (last tick of a scenario has no next tick)
    df = df.filter(pl.col(TARGET_COL).is_not_null())

    # Cast boolean monitor columns to int8 — works for Bool dtype and "true"/"false" strings
    df = df.with_columns(
        [_bool_to_int8(c) for c in BOOL_COLS]
        + [_bool_to_int8(TARGET_COL)]
    )

    # Nulls in continuous columns (no vehicle in that grid cell) are left as NaN
    # so LightGBM can route them to the optimal branch natively.
    pdf = df.to_pandas()
    pdf["ego_maneuver_lane_change"] = pd.Categorical(
        pdf["ego_maneuver_lane_change"], categories=LANE_CHANGE_CATEGORIES
    )

    X = pdf[FEATURE_COLS]
    y = pdf[TARGET_COL].to_numpy()

    return X, y


def _decode_categorical_bitmask(bitmask: int, categories: list[str]) -> list[str]:
    return [cat for i, cat in enumerate(categories) if bitmask & (1 << i)]


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

    if decision == "==" and fname == "ego_maneuver_lane_change":
        # Categorical split: threshold is a bitmask of category indices that go left
        left_cats = _decode_categorical_bitmask(int(threshold), LANE_CHANGE_CATEGORIES)
        right_cats = [c for c in LANE_CHANGE_CATEGORIES if c not in left_cats]
        print(f"{prefix}|--- {fname} in {{{', '.join(left_cats)}}}")
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
        min_split_gain=1.0,
        n_jobs=args.n_jobs,
        class_weight="balanced",    # compensates for the ~1.4% positive rate
        random_state=0,
        verbose=-1,
    )
    clf.fit(X, y)

    booster = clf.booster_
    model_info = booster.dump_model()
    num_leaves = model_info["tree_info"][0]["num_leaves"]

    # Use the booster directly to avoid a sklearn feature-name warning on raw numpy input
    raw_preds = booster.predict(X)
    train_acc = np.mean((raw_preds > 0.5).astype(int) == y)
    print(f"Leaves: {num_leaves}  |  Training accuracy: {train_acc:.4f}\n")
    print_tree(booster, list(X.columns))

    if args.output:
        try:
            graph = lgb.create_tree_digraph(
                booster,
                tree_index=0,
                show_info=["split_gain", "internal_count", "leaf_count"],
                precision=4,
            )
            with open(args.output, "w") as f:
                f.write(graph.source)
            print(f"\nGraphviz dot file written to: {args.output}")
            print("Render with:  dot -Tpng tree.dot -o tree.png")
        except Exception as exc:
            print(f"Warning: could not write dot file ({exc}). Install graphviz: pip install graphviz")


if __name__ == "__main__":
    main()
