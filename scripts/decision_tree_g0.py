"""
Decision tree classifier for next_tick_monitor_g0_Accidents_failed.

Loads a Parquet file produced by export_parquet.py, fits a single LightGBM
tree using all available cores, and prints the tree as indented text.

Usage:
    python decision_tree_g0.py <path-to-parquet> [options]

Feature groups (all enabled by default, disable with --no-<group>):
    --monitors            Current-tick monitor states (7 cols)
    --ego-maneuver        Ego maneuver: speed, lane change (2 cols)
    --ego-state           Ego kinematics: speed, accel, bumper positions (4 cols)
    --distances           Bumper-to-bumper distances per grid cell (8 cols)
    --neighbor-kinematics Per-neighbour speed, accel, position, diffs (48 cols)
    --time-gaps           Per-neighbour TTC and time gap (16 cols)

Dependencies:
    pip install polars lightgbm numpy pandas
    pip install graphviz   # only needed for --output
"""

import argparse
import math
import sys

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl


_NEIGHBORS = [
    "front", "rear",
    "front_left", "front_right",
    "rear_left", "rear_right",
    "left", "right",
]

FEATURE_GROUPS: dict[str, list[str]] = {
    "monitors": [
        "monitor_g0_Accidents_failed",
        "monitor_g1_SafeDistanceToPrecedingVehicle_failed",
        "monitor_g2_emergencyBraking_failed",
        "monitor_g3_MaximumSpeedLimit_failed",
        "monitor_g4_TrafficFlow_failed",
        "monitor_i1_Stopping_failed",
        "monitor_i2_DrivingFasterThenLeftTraffic_failed",
    ],
    "ego-maneuver": [
        "ego_maneuver_speed",
        "ego_maneuver_lane_change",
    ],
    "ego-state": [
        "ego_speed_mps",
        "ego_accel_mps2",
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


def load_and_prepare(path: str, feature_cols: list[str]) -> tuple[pd.DataFrame, np.ndarray]:
    df = pl.read_parquet(path, columns=feature_cols + [TARGET_COL])

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

    X = pdf[feature_cols]
    y = pdf[TARGET_COL].to_numpy()

    return X, y


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


def print_tree(booster: lgb.Booster, feature_names: list[str]) -> None:
    model = booster.dump_model()
    _print_node(model["tree_info"][0]["tree_structure"], feature_names)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("parquet", help="Path to the Parquet export of metric_failed_monitors")
    parser.add_argument("--max-depth", type=int, default=None, help="Maximum tree depth (-1 = unlimited)")
    parser.add_argument("--num-leaves", type=int, default=31, help="Maximum number of leaves (default: 31)")
    parser.add_argument("--n-jobs", type=int, default=96, help="CPU threads for LightGBM (default: 96)")
    parser.add_argument("--output", default=None, help="Write Graphviz .dot file to this path")

    # Feature group toggles — all enabled by default
    group_args = parser.add_argument_group("feature groups (all on by default)")
    group_args.add_argument(
        "--monitors", default=True, action=argparse.BooleanOptionalAction,
        help="Current-tick monitor states: g0–g4, i1–i2 (7 cols)",
    )
    group_args.add_argument(
        "--ego-maneuver", default=True, action=argparse.BooleanOptionalAction,
        help="Ego maneuver: planned speed, lane-change direction (2 cols)",
    )
    group_args.add_argument(
        "--ego-state", default=True, action=argparse.BooleanOptionalAction,
        help="Ego kinematics: speed, accel, front/back bumper position (4 cols)",
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
        "monitors":            args.monitors,
        "ego-maneuver":        args.ego_maneuver,
        "ego-state":           args.ego_state,
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
    print(f"Feature groups enabled:  {', '.join(enabled)}")
    if disabled:
        print(f"Feature groups disabled: {', '.join(disabled)}")
    print(f"Total feature columns:   {len(feature_cols)}\n")

    X, y = load_and_prepare(args.parquet, feature_cols)
    print(f"Loaded {len(X):,} rows  |  positives: {y.sum():,} ({y.mean():.1%})")

    clf = lgb.LGBMClassifier(
        n_estimators=1,
        learning_rate=1.0,
        max_depth=args.max_depth if args.max_depth else -1,
        num_leaves=args.num_leaves,
        min_split_gain=1.0,
        n_jobs=args.n_jobs,
        class_weight="balanced",
        random_state=0,
        verbose=-1,
    )
    clf.fit(X, y)

    booster = clf.booster_
    model_info = booster.dump_model()
    num_leaves = model_info["tree_info"][0]["num_leaves"]

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
                f.write(_decode_dot_lane_change(graph.source))
            print(f"\nGraphviz dot file written to: {args.output}")
            print("Render with:  dot -Tpng tree.dot -o tree.png")
        except Exception as exc:
            print(f"Warning: could not write dot file ({exc}). Install graphviz: pip install graphviz")


if __name__ == "__main__":
    main()
