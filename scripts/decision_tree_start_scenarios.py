"""
Decision trees over starting scenarios (step C of docs/test-bed-plan.md).

For every oracle, leaf bound and feature set, searches the hyperparameters of a single LightGBM
tree with Optuna, refits the best one and assigns every starting scenario to a leaf. Everything is
read from and written to the database:

- features: the baseline's tick-0 rows of metric_failed_monitors (tick 0 is identical for every
  controller), ego_lane plus TTC and time gap to the six neighbours, NULL kept as missing;
- kill matrix: kill_matrices / kill_matrix_mutants / kill_matrix_baseline_violations, written by
  ./gradlew runKillMatrices (latest per oracle unless --kill-matrix-ids is given);
- results: start_scenario_grouping_runs (kind = 'tree'), start_scenario_grouping_mutant_splits and
  start_scenario_group_assignments. ./gradlew runStartScenarioTscGroupings creates these tables.

The mutation operator of each mutant (for stratified folds and splits) comes from the
"AUTO GENERATED COMMENT Mutation Operator:" line in src/main/kotlin/tools/aqua/stars/sumo/mutants.

Rows are collapsed (plan, step C): per pool scenario one row labelled 1 weighted by its killed
training pairs and one labelled 0 weighted by its non-killed ones, with balanced class weights
folded in. For a single tree this gives the same splits as the full pair table; --check-collapse
asserts that on 2,000 scenarios. The minimum leaf size is searched in scenarios and passed as
min_data_in_leaf = 2 * scenarios, since both rows of a scenario always share a leaf. The model is
LightGBM's native equivalent of LGBMClassifier(n_estimators=1, learning_rate=1.0, random_state=0),
so each fold's Dataset is binned once and reused by every trial.

Usage:
    python scripts/decision_tree_start_scenarios.py --uri postgresql://user:pw@host:5432/db
        [--condition scripts/conditions/full_run.json] [--oracles G0,TTC,TG]
        [--kill-matrix-ids G0=1,TTC=2,TG=3] [--leaf-bounds 16,68] [--trials 200] [--folds 5]
        [--feature-sets with_ego_lane,without_ego_lane] [--check-collapse] [--n-jobs 8]
        [--mutant-ids 1,2,3] [--mutant-numbers 10,11] [--train-fraction 0.7] [--seed 0]
        [--rare-below 0.01]

Manual mutant split, as in decision_tree_g0.py: --mutant-ids / --mutant-numbers (union) restrict
the run to hand-picked mutants; --train-fraction then splits them into training and scoring
mutants, stratified by operator (1.0, the default, trains and scores on all of them).

Common vs rare mutants: --rare-below SHARE trains, per oracle, on the kept mutants killed in at
least SHARE of the pool scenarios and scores on the rarer ones. Condition rare_below_<SHARE>.

Dependencies (all in the Dockerfile):
    pip install numpy lightgbm optuna scikit-learn psycopg2-binary
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import optuna
import psycopg2
from psycopg2.extras import execute_values
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import StratifiedGroupKFold

REPO = Path(__file__).resolve().parent.parent
MUTANTS_DIR = REPO / "src/main/kotlin/tools/aqua/stars/sumo/mutants"
OPERATOR_LINE = re.compile(r"AUTO GENERATED COMMENT Mutation Operator: (\w+)")

NEIGHBOURS = ["front", "rear", "front_left", "front_right", "rear_left", "rear_right"]
TTC_TG = [f"surrounding_{n}_{m}" for n in NEIGHBOURS for m in ("ttc_s", "tg_s")]
FEATURE_SETS = {"with_ego_lane": ["ego_lane"] + TTC_TG, "without_ego_lane": TTC_TG}
LANE_INDEX = {"RIGHT": 0, "MIDDLE": 1, "LEFT": 2}  # HighwayLane names as stored in ego_lane
KIND_TREE = "tree"
CHECK_SCENARIOS = 2000


class Log:
    """Prints and keeps the lines, so the run row gets the same log."""

    def __init__(self) -> None:
        self.lines: list[str] = []

    def __call__(self, msg: str) -> None:
        print(msg, flush=True)
        self.lines.append(msg)

    def text(self) -> str:
        return "\n".join(self.lines)


# ---------------------------------------------------------------- reading


def read_features(cur) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """Scenario ids (ascending) and one float column per feature, NaN for NULL."""
    cur.execute(
        f"""
        SELECT DISTINCT ON (m.scenario_config_id) m.scenario_config_id, m.ego_lane, {", ".join("m." + c for c in TTC_TG)}
        FROM metric_failed_monitors m JOIN mutants mu ON mu.id = m.mutant_id
        WHERE mu.mutant_number = -1 AND m.tick = 0
        ORDER BY m.scenario_config_id, m.run_id
        """
    )
    rows = cur.fetchall()
    ids = np.array([r[0] for r in rows], dtype=np.int64)
    cols = {"ego_lane": np.array([np.nan if r[1] is None else LANE_INDEX[r[1]] for r in rows], dtype=np.float64)}
    for j, c in enumerate(TTC_TG):
        cols[c] = np.array([np.nan if r[2 + j] is None else r[2 + j] for r in rows], dtype=np.float64)
    return ids, cols


def read_mutants(cur) -> dict[int, tuple[int, str, str | None]]:
    """mutants.id -> (mutant_number, class_name, operator or None)."""
    cur.execute("SELECT id, mutant_number, class_name FROM mutants")
    out = {}
    for mid, number, cls in cur.fetchall():
        path = MUTANTS_DIR / f"{cls}.kt"
        found = OPERATOR_LINE.search(path.read_text(encoding="utf-8")) if path.exists() else None
        out[mid] = (number, cls, found.group(1) if found else None)
    return out


def latest_kill_matrix_id(cur, oracle: str) -> int:
    cur.execute("SELECT max(id) FROM kill_matrices WHERE oracle = %s", (oracle,))
    (mid,) = cur.fetchone()
    if mid is None:
        sys.exit(f"No stored {oracle} kill matrix; run ./gradlew runKillMatrices first")
    return mid


class KillMatrix:
    """A stored kill matrix: pool scenario ids, kept mutants and a (pool x kept) bool matrix."""

    def __init__(self, cur, matrix_id: int) -> None:
        cur.execute("SELECT oracle, thresholds_json, lc_window_ticks FROM kill_matrices WHERE id = %s", (matrix_id,))
        self.id = matrix_id
        self.oracle, thresholds, self.lc_window_ticks = cur.fetchone()
        self.thresholds = json.loads(thresholds)
        cur.execute("SELECT id FROM scenario_starting_configurations")
        all_ids = np.array(sorted(r[0] for r in cur.fetchall()), dtype=np.int64)
        cur.execute("SELECT scenario_config_id FROM kill_matrix_baseline_violations WHERE kill_matrix_id = %s", (matrix_id,))
        removed = np.array([r[0] for r in cur.fetchall()], dtype=np.int64)
        self.pool = np.setdiff1d(all_ids, removed)
        cur.execute(
            "SELECT mutant_id, role, representative_mutant_id, kill_scenario_ids FROM kill_matrix_mutants "
            "WHERE kill_matrix_id = %s ORDER BY mutant_id",
            (matrix_id,),
        )
        rows = cur.fetchall()
        kept = [r for r in rows if r[1] == "KEPT"]
        self.mutant_ids = [r[0] for r in kept]
        self.representative = {r[0]: r[2] for r in rows if r[1] == "DUPLICATE"}
        self.non_killing = {r[0] for r in rows if r[1] == "NON_KILLING"}
        self.kills = np.zeros((len(self.pool), len(kept)), dtype=bool)
        for j, r in enumerate(kept):
            self.kills[np.searchsorted(self.pool, np.array(r[3], dtype=np.int64)), j] = True


# ---------------------------------------------------------------- mutant split


def split_mutants(args, spec: dict, km: KillMatrix, mutants: dict, log: Log) -> tuple[list[int], list[int], bool]:
    """Training and scoring mutants (kept columns of km) and whether the selection was manual."""
    picked: set[int] = set()
    if args.mutant_ids:
        picked |= {int(x) for x in args.mutant_ids.split(",")}
    if args.mutant_numbers:
        by_number = {number: mid for mid, (number, _, _) in mutants.items()}
        for n in (int(x) for x in args.mutant_numbers.split(",")):
            if n not in by_number:
                sys.exit(f"Unknown mutant number {n}")
            picked.add(by_number[n])
    manual = bool(picked)

    if args.rare_below is not None:
        if manual or args.train_fraction < 1.0:
            sys.exit("--rare-below picks the mutants itself; do not combine it with --mutant-ids, "
                     "--mutant-numbers or --train-fraction")
        share = km.kills.mean(axis=0)
        log("  kill share per kept mutant: " + ", ".join(f"{m}: {s:.4%}" for m, s in zip(km.mutant_ids, share)))
        training = [m for m, s in zip(km.mutant_ids, share) if s >= args.rare_below]
        scoring = [m for m, s in zip(km.mutant_ids, share) if s < args.rare_below]
        if not training or not scoring:
            sys.exit(f"{km.oracle}: --rare-below {args.rare_below:g} leaves no common or no rare mutant")
        return training, scoring, False

    if not manual:
        training = km.mutant_ids if spec["trainingMutants"] == "all" else picked_to_kept(spec["trainingMutants"], km, log)
        if spec["scoringMutants"] == "same":
            return list(training), list(training), False
        sys.exit("Only scoringMutants = 'same' is implemented; other conditions come with the pre-study")

    selected = picked_to_kept(sorted(picked), km, log)
    if args.train_fraction >= 1.0:
        return selected, selected, True
    rng = np.random.default_rng(args.seed)
    training, scoring = [], []
    by_operator: dict[str, list[int]] = {}
    for m in selected:
        by_operator.setdefault(mutants[m][2] or "unknown", []).append(m)
    for op, ms in sorted(by_operator.items()):
        ms = list(rng.permutation(ms))
        k = int(round(args.train_fraction * len(ms)))
        training += ms[:k]
        scoring += ms[k:]
        log(f"  operator {op}: {k} training, {len(ms) - k} scoring")
    if not training or not scoring:
        sys.exit("The split left no training or no scoring mutant; choose more mutants or another fraction")
    return sorted(int(m) for m in training), sorted(int(m) for m in scoring), True


def picked_to_kept(ids, km: KillMatrix, log: Log) -> list[int]:
    """Hand-picked ids as kept columns: duplicates become their representative, non-killing ones drop out."""
    out = []
    for m in ids:
        if m in km.representative:
            log(f"  mutant {m} has the same kill set as {km.representative[m]} under {km.oracle}; using {km.representative[m]}")
            m = km.representative[m]
        elif m in km.non_killing:
            log(f"  mutant {m} kills nowhere under {km.oracle}; dropped")
            continue
        elif m not in km.mutant_ids:
            log(f"  mutant {m} is not in {km.oracle} kill matrix {km.id} (AiPilot or baseline?); dropped")
            continue
        if m not in out:
            out.append(m)
    if not out:
        sys.exit(f"No usable mutant left for {km.oracle}")
    return sorted(out)


# ---------------------------------------------------------------- learning


def collapsed(x: np.ndarray, kills: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Two rows per scenario (label 1 / 0) weighted by killed / non-killed pairs, balanced."""
    pos = kills.sum(axis=1).astype(np.float64)
    neg = kills.shape[1] - pos
    total = pos.sum() + neg.sum()
    w = np.concatenate([pos * total / (2 * pos.sum()), neg * total / (2 * neg.sum())])
    y = np.concatenate([np.ones(len(x)), np.zeros(len(x))])
    return np.vstack([x, x]), y, w


def params(num_leaves: int, max_depth: int, min_scenarios: int, min_split_gain: float, n_jobs: int) -> dict:
    return {
        "objective": "binary",
        "learning_rate": 1.0,
        "num_leaves": num_leaves,
        "max_depth": max_depth,
        "min_data_in_leaf": 2 * min_scenarios,
        "min_gain_to_split": min_split_gain,
        "seed": 0,
        "deterministic": True,
        "num_threads": n_jobs,
        "verbosity": -1,
    }


def dataset(x: np.ndarray, kills: np.ndarray, names: list[str]) -> lgb.Dataset:
    xs, y, w = collapsed(x, kills)
    # feature_pre_filter off: every trial sets its own min_data_in_leaf on the same binned Dataset.
    return lgb.Dataset(
        xs, label=y, weight=w, feature_name=names, free_raw_data=False, params={"feature_pre_filter": False}
    ).construct()


def weighted_auc(score: np.ndarray, kills: np.ndarray) -> float | None:
    """ROC-AUC over the pairs of kills' mutants, each pair weight 1, from per-scenario scores."""
    pos = kills.sum(axis=1)
    neg = kills.shape[1] - pos
    y = np.concatenate([np.ones(len(score)), np.zeros(len(score))])
    w = np.concatenate([pos, neg]).astype(np.float64)
    keep = w > 0
    if len(np.unique(y[keep])) < 2:
        return None
    return float(roc_auc_score(y[keep], np.concatenate([score, score])[keep], sample_weight=w[keep]))


def tree_depth(node: dict) -> int:
    if "leaf_index" in node:
        return 0
    return 1 + max(tree_depth(node["left_child"]), tree_depth(node["right_child"]))


def same_tree(a: dict, b: dict) -> bool:
    """Same split features, thresholds, missing-value directions and leaf values."""
    if ("leaf_index" in a) != ("leaf_index" in b):
        return False
    if "leaf_index" in a:
        return bool(np.isclose(a["leaf_value"], b["leaf_value"]))
    return (
        a["split_feature"] == b["split_feature"]
        and np.isclose(a["threshold"], b["threshold"])
        and a["default_left"] == b["default_left"]
        and a["missing_type"] == b["missing_type"]
        and same_tree(a["left_child"], b["left_child"])
        and same_tree(a["right_child"], b["right_child"])
    )


def check_collapse(x: np.ndarray, kills: np.ndarray, names: list[str], log: Log) -> None:
    """Plan step C: on 2,000 scenarios the raw pair table and the collapsed rows give the same tree."""
    rng = np.random.default_rng(0)
    sub = np.sort(rng.choice(len(x), size=min(CHECK_SCENARIOS, len(x)), replace=False))
    xs, ks = x[sub], kills[sub]
    n_pos, n_neg = ks.sum(), ks.size - ks.sum()
    total = ks.size
    raw_x = np.repeat(xs, ks.shape[1], axis=0)
    raw_y = ks.reshape(-1).astype(np.float64)
    raw_w = np.where(raw_y == 1, total / (2 * n_pos), total / (2 * n_neg))
    # Weight-based leaf limit only: counts differ between the two tables, weights do not.
    p = {"objective": "binary", "learning_rate": 1.0, "num_leaves": 16, "min_data_in_leaf": 1,
         "min_sum_hessian_in_leaf": 5.0, "min_data_in_bin": 1, "seed": 0, "deterministic": True, "verbosity": -1}
    raw = lgb.train(p, lgb.Dataset(raw_x, raw_y, weight=raw_w, feature_name=names), num_boost_round=1)
    cx, cy, cw = collapsed(xs, ks)
    col = lgb.train(p, lgb.Dataset(cx, cy, weight=cw, feature_name=names), num_boost_round=1)
    ta = raw.dump_model()["tree_info"][0]["tree_structure"]
    tb = col.dump_model()["tree_info"][0]["tree_structure"]
    if not same_tree(ta, tb):
        sys.exit("Collapse check FAILED: raw pairs and collapsed rows give different trees")
    log(f"Collapse check passed: {len(raw_y)} raw pairs and {len(cy)} collapsed rows give the same "
        f"tree ({raw.dump_model()['tree_info'][0]['num_leaves']} leaves)")


def search(x, kills, folds, names, bound, trials, seed, n_jobs, log: Log):
    """Optuna TPE over the tree's hyperparameters; score = mean weighted AUC over the inner folds."""
    fold_data = [(dataset(x, kills[:, tr], names), kills[:, va]) for tr, va in folds]

    def objective(trial: optuna.Trial) -> float:
        p = params(
            trial.suggest_int("num_leaves", 4, bound, log=True),
            trial.suggest_int("max_depth", 2, 20),
            trial.suggest_int("min_scenarios_per_leaf", 10, 2500, log=True),
            trial.suggest_float("min_split_gain", 0.0, 1.0),
            n_jobs,
        )
        aucs = []
        for ds, va in fold_data:
            booster = lgb.train(p, ds, num_boost_round=1)
            auc = weighted_auc(booster.predict(x), va)
            if auc is not None:
                aucs.append(auc)
        return float(np.mean(aucs)) if aucs else 0.5

    optuna.logging.set_verbosity(optuna.logging.WARNING)
    study = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=seed))
    study.optimize(objective, n_trials=trials)
    log(f"    best mean CV AUC {study.best_value:.4f} with {study.best_params}")
    return study.best_params, study.best_value


def inner_folds(training: list[int], km: KillMatrix, mutants: dict, n_folds: int, seed: int):
    """Folds over the training mutants (columns of km.kills), grouped by mutant, stratified by operator."""
    cols = np.array([km.mutant_ids.index(m) for m in training])
    k = min(n_folds, len(cols))
    if k < 2:
        sys.exit(f"{km.oracle}: {len(cols)} training mutant(s), too few for inner folds")
    strata = np.array([mutants[m][2] or "unknown" for m in training])
    cv = StratifiedGroupKFold(n_splits=k, shuffle=True, random_state=seed)
    return [(cols[tr], cols[va]) for tr, va in cv.split(cols.reshape(-1, 1), strata, groups=cols)]


# ---------------------------------------------------------------- storing


def git_commit() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def store(conn, run: dict, splits: list[tuple[int, bool, bool]], assignments: list[tuple[int, int]]) -> int:
    with conn, conn.cursor() as cur:
        cols = ", ".join(run)
        cur.execute(
            f"INSERT INTO start_scenario_grouping_runs (created_at, {cols}) "
            f"VALUES (now(), {', '.join(['%s'] * len(run))}) RETURNING id",
            list(run.values()),
        )
        (run_id,) = cur.fetchone()
        execute_values(
            cur,
            "INSERT INTO start_scenario_grouping_mutant_splits (run_id, mutant_id, trained_on, scored_on) VALUES %s",
            [(run_id, m, t, s) for m, t, s in splits],
        )
        execute_values(
            cur,
            "INSERT INTO start_scenario_group_assignments (run_id, scenario_config_id, group_id) VALUES %s",
            [(run_id, s, g) for s, g in assignments],
            page_size=10000,
        )
    return run_id


# ---------------------------------------------------------------- main


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--uri", required=True, help="postgresql://user:password@host:port/database")
    ap.add_argument("--condition", default=str(REPO / "scripts/conditions/full_run.json"))
    ap.add_argument("--oracles", help="comma-separated, default from the condition")
    ap.add_argument("--kill-matrix-ids", help="ORACLE=ID,... (default: latest per oracle)")
    ap.add_argument("--leaf-bounds", help="comma-separated, default from the condition")
    ap.add_argument("--trials", type=int, help="Optuna trials per leaf bound, default from the condition")
    ap.add_argument("--folds", type=int, help="inner folds, default from the condition")
    ap.add_argument("--feature-sets", help="comma-separated of " + ", ".join(FEATURE_SETS))
    ap.add_argument("--mutant-ids", help="hand-picked mutants.id values")
    ap.add_argument("--mutant-numbers", help="hand-picked mutants.mutant_number values")
    ap.add_argument("--train-fraction", type=float, default=1.0)
    ap.add_argument("--rare-below", type=float, metavar="SHARE",
                    help="train on mutants killed in >= SHARE of the pool, score on the rarer ones")
    ap.add_argument("--seed", type=int, help="seed of the search and the split, default from the condition")
    ap.add_argument("--check-collapse", action="store_true", help="assert raw pairs == collapsed rows first")
    ap.add_argument("--n-jobs", type=int, default=8, help="LightGBM threads")
    args = ap.parse_args()

    spec = json.loads(Path(args.condition).read_text(encoding="utf-8"))
    oracles = args.oracles.split(",") if args.oracles else spec["oracles"]
    bounds = [int(b) for b in args.leaf_bounds.split(",")] if args.leaf_bounds else spec["leafBounds"]
    trials = args.trials or spec["trials"]
    n_folds = args.folds or spec["folds"]
    feature_sets = args.feature_sets.split(",") if args.feature_sets else spec["featureSets"]
    seed = spec["seed"] if args.seed is None else args.seed
    args.seed = seed
    fixed_ids = dict(kv.split("=") for kv in args.kill_matrix_ids.split(",")) if args.kill_matrix_ids else {}
    if spec.get("visiblePairs", "all") != "all":
        sys.exit("Only visiblePairs = 'all' is implemented; reduced-data conditions come with the pre-study")
    unknown = set(feature_sets) - set(FEATURE_SETS)
    if unknown:
        sys.exit(f"Unknown feature sets {unknown}")

    conn = psycopg2.connect(args.uri)
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass('start_scenario_group_assignments')")
        if cur.fetchone()[0] is None:
            sys.exit("Grouping tables missing; run ./gradlew runStartScenarioTscGroupings first")
        t = time.time()
        scenario_ids, features = read_features(cur)
        mutants = read_mutants(cur)
        print(f"Read {len(scenario_ids)} tick-0 feature rows and {len(mutants)} mutants in {time.time() - t:.1f} s")
    commit = git_commit()

    for oracle in oracles:
        log = Log()
        with conn.cursor() as cur:
            km = KillMatrix(cur, int(fixed_ids.get(oracle) or latest_kill_matrix_id(cur, oracle)))
        log(f"{oracle}: kill matrix {km.id} (thresholds {km.thresholds}, lane-change window "
            f"{km.lc_window_ticks} ticks), pool {len(km.pool)}, {len(km.mutant_ids)} kept mutants")
        missing = np.setdiff1d(km.pool, scenario_ids)
        if len(missing):
            sys.exit(f"{len(missing)} pool scenarios have no baseline tick-0 row")
        in_pool = np.searchsorted(scenario_ids, km.pool)
        training, scoring, manual = split_mutants(args, spec, km, mutants, log)
        if args.rare_below is not None:
            condition = f"rare_below_{args.rare_below:g}"
        else:
            condition = "manual" if manual else spec["name"]
        log(f"  condition {condition}: training {training}, scoring {scoring}")
        train_kills = km.kills[:, [km.mutant_ids.index(m) for m in training]]
        folds = inner_folds(training, km, mutants, n_folds, seed)
        splits = [(m, m in training, m in scoring) for m in sorted(set(training) | set(scoring))]

        for fs in feature_sets:
            names = FEATURE_SETS[fs]
            x_all = np.column_stack([features[c] for c in names])
            x = x_all[in_pool]
            if args.check_collapse:
                check_collapse(x, train_kills, names, log)
            for bound in bounds:
                run_log = Log()
                run_log.lines = list(log.lines)
                run_log(f"  {fs}, leaf bound {bound}: {trials} trials x {len(folds)} folds")
                t0 = time.time()
                best, cv_auc = search(x, km.kills, folds, names, bound, trials, seed, args.n_jobs, run_log)
                t1 = time.time()
                p = params(best["num_leaves"], best["max_depth"], best["min_scenarios_per_leaf"],
                           best["min_split_gain"], args.n_jobs)
                booster = lgb.train(p, dataset(x, train_kills, names), num_boost_round=1)
                t2 = time.time()
                leaves = booster.predict(x_all, pred_leaf=True).reshape(-1).astype(int)
                t3 = time.time()
                tree = booster.dump_model()["tree_info"][0]
                run = {
                    "kind": KIND_TREE, "kill_matrix_id": km.id, "condition": condition, "feature_set": fs,
                    "feature_columns": names, "manual_mutant_selection": manual,
                    "train_fraction": args.train_fraction, "leaf_bound": bound, "seed": seed,
                    "n_trials": trials, "n_folds": len(folds), "hp_num_leaves": best["num_leaves"],
                    "hp_max_depth": best["max_depth"], "hp_min_scenarios_per_leaf": best["min_scenarios_per_leaf"],
                    "hp_min_split_gain": best["min_split_gain"], "cv_roc_auc": cv_auc,
                    "learned_num_leaves": tree["num_leaves"], "learned_max_depth": tree_depth(tree["tree_structure"]),
                    "n_groups": int(len(np.unique(leaves))), "model_text": booster.model_to_string(),
                    "git_commit": commit, "seconds_search": t1 - t0, "seconds_refit": t2 - t1,
                    "seconds_labelling": t3 - t2,
                }
                run_log(f"    tree: {run['learned_num_leaves']} leaves, depth {run['learned_max_depth']}, "
                        f"{run['n_groups']} groups over {len(leaves)} scenarios; search {t1 - t0:.1f} s, "
                        f"refit {t2 - t1:.1f} s, labelling {t3 - t2:.1f} s")
                run["log_text"] = run_log.text()
                run_id = store(conn, run, splits, list(zip(scenario_ids.tolist(), leaves.tolist())))
                print(f"    stored as start_scenario_grouping_runs.id = {run_id}", flush=True)
    conn.close()


if __name__ == "__main__":
    main()
