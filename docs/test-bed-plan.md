# Test bed from learned scenario classes: implementation plan

Status: agreed with the supervisor on 2026-11-17. This file is the brief for anyone (human or agent)
implementing the next steps. It records what was decided, what is only proposed, and what is still
open. Do not decide open items silently: make them parameters with the suggested default and log
the value used.

Longer rationale, with tables: the protocol document "STARS coverage significance: tree learning and
comparison protocol" (Claude Docs, owned by Till Schallau). The pipeline illustration
`stars-pipeline-and-plan.svg` shows the same plan as steps A to F.

## 1. Goal

Find out whether drawing **starting scenarios** by learned or hand-built classes kills mutants
faster than drawing them at random. The end product is a reproducible test bed: a list of starting
scenario IDs plus the classifier that groups them, which can be run against new autopilot versions.

The earlier attempt (`scripts/decision_tree_g0.py`, `DrawTicksWithDecisionTreeGroupingPostEvaluation`)
learned on ticks and drew ticks. A drawn tick cannot be re-run: replaying SUMO from a mid-run state
does not reproduce the original run. Starting scenarios can be re-run, because the state at tick 0
is identical for every controller (see `NOTES.md`). Everything below therefore works on starting
scenarios.

## 2. What already exists

| Item | Where | Notes |
| --- | --- | --- |
| Tick table | `metric_failed_monitors` (`MetricFailedMonitorsTable.kt`) | One row per run and tick; ticks every 100 ms, at most 41 per run (0.0 s to 4.0 s); a run stops at the first collision |
| Keys | `scenario_config_id`, `mutant_id`, `run_id`, `tick` | 196,608 starting scenarios |
| Accident flag | `monitor_g0_Accidents_failed`, `next_tick_monitor_g0_Accidents_failed` | The other six monitors are **not** used (see decisions) |
| Own-lane front and rear | `surrounding_front_ttc_s`, `surrounding_front_tg_s`, `surrounding_rear_ttc_s`, `surrounding_rear_tg_s` | TTC is NULL when not closing; treat NULL as +infinity |
| Neighbour cells | `surrounding_{front,rear}_{left,right}_{ttc_s,tg_s}` | Used as tree **input** only |
| Ego lane | `ego_lane` | Also needed to detect lane changes |
| Project TSC class | `current_tsc_instance_id` | Read it on tick-0 rows |
| Baseline (unmutated autopilot) | `mutants.mutant_number = -1`, `class_name = 'Autopilot'` | Seeded by `MutantGenerator.seedBaseline()` |
| Mutation operator per mutant | Comment `AUTO GENERATED COMMENT Mutation Operator: <name>` in each `sumo/mutants/AutopilotMutantN.kt` | 144 ArithmeticReplacement, 19 UnaryRemoval, 11 LogicalReplacement (174 mutants in the registry) |
| Accident per pair, not baseline-relative | Materialized view `mutant_scenario_g0_violations` (`DbBootstrap.kt`) | Template for step A |
| Suite sizes, repetitions | `NEXT_TICK_SUITE_SIZES`, `REPETITIONS = 100` in `PostEvaluation.kt` | Reuse the same values |
| Old tick-level pipeline | `scripts/decision_tree_g0.py`, `scripts/decision_tree_pipeline.sh`, `DrawTicksWithDecisionTreeGroupingPostEvaluation.kt` | Keep it; write the new pipeline alongside, do not modify the old one |

## 3. Decisions

### Decided

| # | Decision | Agreed |
| --- | --- | --- |
| D1 | Three kill matrices per (starting scenario, mutant): accident (G0), TTC below threshold, TG below threshold, each "ever during the run" | Supervisor, 2026-11-17 |
| D2 | One tree per oracle, so three trees per run; compared with the two hand-built TSCs (`tsc()` and `tscWithStaticDistances()`); greedy as upper bound | Supervisor, 2026-11-17 |
| D3 | Leaf labels are assigned to starting scenarios only, not to ticks | Supervisor, 2026-11-17 |
| D4 | Hyperparameters are searched again in every run, on nested splits; several upper bounds on the number of leaves are tried | Supervisor, 2026-11-17 |
| D5 | Experiments vary the training data and the training mutants, each at random and structurally; the only structure the mutants have is the mutation operator | Supervisor, 2026-11-17 |
| D6 | Order of work: (1) one full run on all data, (2) coarse pre-study, (3) main study | Supervisor, 2026-11-17 |
| D7 | The full run trains and scores on all data: best case for the result, worst case for the runtime. Record wall-clock time per stage | Supervisor, 2026-11-17 |
| D8 | Highway traffic waits until these results exist | Supervisor, 2026-11-17 |
| D9 | A kill counts only where the unmutated autopilot does not violate the same oracle in the same scenario | Till, 2026-11-14 |
| D10 | Draw until every scenario is drawn; read results at `NEXT_TICK_SUITE_SIZES`; 100 repetitions | Till, 2026-11-14 |
| D11 | The other six monitors (G1 to G4, I1, I2) are not used: their results are not trusted yet | Till, 2026-11-14 |

### Proposed, pending confirmation with the supervisor

| # | Proposal | Default to implement |
| --- | --- | --- |
| P1 | In reduced-data conditions, 30% of mutants are held out first (stratified by operator) and suites are scored on them; the reduction applies only to the training mutants' pairs | Implement; make the held-out share a parameter |
| P2 | Pre-study levels and repeats (section 5) | Implement as a config file, not hard-coded |
| P3 | Leaf bounds 16, 68, 160, 512 (68 and 160 are the TSC class counts at tick 0) | Default list, configurable |
| P4 | Oracles use only the vehicle in front and behind on the ego's lane; neighbouring lanes stay tree input | Implement |
| P5 | TTC and TG thresholds are chosen by a sweep that uses only the baseline (step A.2a): per oracle and side, the largest threshold where the baseline violates at most 5% of scenarios. Mutant data does not enter the choice (Till, 2026-11-17) | Implement; `max_pool_loss = 0.05` as a parameter |
| P6 | Front and rear thresholds are separate parameters: `ttc_front`, `ttc_rear`, `tg_front`, `tg_rear` (Till, 2026-11-17) | Implement; equal front and rear values unless the sweep picks otherwise |

### Still open (parameters, with suggested defaults)

| # | Question | Suggested default |
| --- | --- | --- |
| O1 | TTC thresholds (front, rear) | Set by the sweep (P5) over {1.0, 1.5, 2.0, 2.5, 3.0, 4.0} s; fallback if the sweep is skipped: 1.5 s. The hand-built TSC uses 2.0 s |
| O2 | TG thresholds (front, rear) | Set by the sweep (P5) over {0.5, 0.7, 0.9, 1.0, 1.2, 1.5} s; fallback if the sweep is skipped: 0.9 s (the autopilot aims for 1.0 s headway). The hand-built TSC uses 1.0 s |
| O3 | Rear vehicle: count always, or only shortly after an ego lane change | Implement both: `always` and `after_lane_change` (20 ticks = 2 s after the tick where `ego_lane` changed); default `after_lane_change` |
| O4 | Mutant count: the discussion used 172, the registry has 174 | Use all mutants present in the data after cleaning (section 4, step A); log the ids |
| O5 | Extra run weighting every mutant equally (pair weight 1 / number of scenarios killing that mutant) | Implement as an option, off by default |
| O6 | Reference tree on the nine raw grid cells of `scenario_starting_configurations` | Optional, not part of the first run |

Why fixed thresholds from the literature fit poorly here (O1, O2, P5, P6): every starting scenario has the same geometry. The
grid generator places vehicles at the cell centres (50, 110 and 170 m), so the bumper gap to the front and rear cell is
about 55 m. Only the speeds differ (ego 25 m/s; calm 16.7, normal 25, speedy 33.3 m/s). At tick 0 the front TG is
therefore about 2.2 s and the TTC is 6.6 s or infinite, so no scenario starts below a plausible threshold. A rough
re-simulation of the baseline's cruise control over 4 s keeps it above about 7 s TTC and 1.7 s TG while following a car.
Even a mutant that never brakes reaches only about 1.3 s TTC, near t = 4 s and behind a calm leader. A TTC threshold
of 1.5 s therefore catches mainly lane changes, cut-ins and collisions, which is close to the accident oracle. The baseline
itself changes lanes with only 20 m free ahead and 15 m behind (TG about 0.8 s and 0.6 s at 25 m/s). Its own lane changes
can therefore break a shared TG threshold, mostly at the rear, and D9 then removes those scenarios. These numbers are an
estimate from the code, not measured; the sweep replaces them.

## 4. Pipeline to implement

Each step lists its output and an acceptance check. Write intermediate results to Parquet so later
steps do not touch the tick table again.

### A. Per-pair outcomes and kill matrices

1. Materialized view or table `scenario_mutant_outcomes`, one row per (`scenario_config_id`,
   `mutant_id`), built from `metric_failed_monitors` in one pass:
   - `accident_any`: G0 failed at any tick, `BOOL_OR(monitor_g0_Accidents_failed)`. The existing
     view uses the next-tick flag instead; both should agree except for an accident at tick 0.
   - `min_front_ttc`, `min_front_tg`, `min_rear_ttc`, `min_rear_tg` over all ticks (NULL as +infinity).
   - `min_rear_ttc_after_lc`, `min_rear_tg_after_lc`: the same, but only over ticks within 20 ticks
     after a tick whose `ego_lane` differs from the previous tick.
   - `n_ticks`, `first_accident_tick`.
   - Implemented in `sqlQueries/scenario_mutant_outcomes.sql` (run with `-v scope=baseline`, then
     `-v scope=mutants`). It also stores `n_lane_changes` (for A.2a), `n_runs` (duplicate check) and
     `lc_window_ticks`.
2. Kill matrices, one per oracle, computed in Python from that table with thresholds as parameters:
   - Oracle TTC: `front_ttc < ttc_front OR rear_ttc[_after_lc] < ttc_rear` (P6); oracle TG likewise
     with `tg_front` and `tg_rear`.
   - Kill = mutant violates and baseline (`mutant_number = -1`) does not, in the same scenario (D9).
   - Scenarios where the baseline violates are **removed from that oracle**: they are neither
     training rows nor drawable test scenarios for it. Save them as a separate list with a flag
     "at least one mutant avoided the violation".
2a. Threshold sweep (P5), in Python over `scenario_mutant_outcomes`, no new table. Per oracle (TTC, TG),
   side (front, rear) and rear mode (O3), for each value in the sweep grid (O1, O2) report: the number of
   scenarios the baseline violates, how many of them follow a baseline lane change, and the remaining
   pool size. Pick the largest value with a baseline-violating share of at most `max_pool_loss`. Only the
   baseline enters this choice. As a description only (full run), also report: killable mutants, the share of
   mutants killed in more than 90% of scenarios, and the Jaccard overlap of each kill matrix with the G0
   matrix. Write the chosen values into the run config.
3. Cleaning, per oracle: drop mutants that kill nowhere; group mutants with identical kill columns
   and keep one representative per group (record the groups). AiPilot is not a training or scoring
   mutant; keep its row as an outside check.

Acceptance: one outcome row per (scenario, controller) and no duplicates per pair; per oracle, a
log of valid scenarios, killable mutants, duplicate groups and baseline-violating scenarios.

### B. Tick-0 features and TSC classes

One row per starting scenario, taken from the baseline's tick-0 row (tick 0 is identical for all
controllers): `ego_lane` plus TTC and TG to the six neighbours (13 columns; NULL stays missing for
LightGBM). Add the project TSC class (`current_tsc_instance_id` at tick 0) and the static-distance TSC
class (evaluate `tscWithStaticDistances()` on the tick-0 state).

Acceptance: 196,608 rows. A re-implementation of the predicates predicts 68 distinct project-TSC
classes and 160 static-TSC classes at tick 0; check against the data and report any difference.

### C. Tree learning (new script, e.g. `scripts/decision_tree_start_scenarios.py`)

Inputs: features (B), one kill matrix (A), a condition spec (JSON: training mutants, which of their
pairs are visible, scoring mutants, seed, thresholds, leaf bound).

- **Rows.** Conceptually one row per visible (scenario, training mutant) pair, label = killed.
  Implement it collapsed: per scenario two rows with identical features, one labelled 1 with weight =
  visible killed pairs, one labelled 0 with weight = visible non-killed pairs. Keep zero-weight rows
  so every scenario has exactly two rows; drop scenarios with no visible pair. For a single
  LightGBM tree this gives the same split gains as the full pair table with about 86 times fewer
  rows (393,216 instead of 33.8 million). Verify once on a subset (e.g. 2,000 scenarios): fit on raw pairs and on collapsed rows and
  assert identical tree structure.
- **Model.** `LGBMClassifier(n_estimators=1, learning_rate=1.0, random_state=0)`, class weighting
  `balanced` applied through the row weights.
- **Search.** Optuna TPE (seed 0), 200 trials per leaf bound. Parameters: `num_leaves` 4 to bound
  (log), `max_depth` 2 to 20, minimum leaf size 20 to 5000 pairs, `min_split_gain` 0 to 1. With
  collapsed rows, `min_child_samples` counts rows, not pairs: express the minimum leaf size via
  `min_sum_hessian_in_leaf`, or search a minimum number of scenarios per leaf and set
  `min_child_samples = 2 * scenarios`. Document which.
- **Scoring a trial.** 5 inner folds over the training mutants, grouped by mutant (stratified by
  operator where possible). Fit on 4 folds, score weighted ROC-AUC (`sample_weight`) on the visible
  pairs of the fifth fold's mutants, average over folds. The scoring mutants of the condition are
  never used here.
- **Final tree.** Refit the best setting on all training data. Record best parameters, mean CV AUC,
  actual leaf count and depth, and wall-clock time of search, refit and labelling.
- **Labels.** Predict the leaf for all 196,608 scenarios (the tree is applied to every scenario,
  including those not visible in training). Store in a new table, e.g.
  `scenario_leaf_assignments(run_id, scenario_config_id, leaf_node_id)`, with run metadata in
  `scenario_tree_runs` (condition, oracle, leaf bound, thresholds, seeds, training and scoring mutant
  ids, git commit, timings). Follow the pattern of `decision_tree_runs`.

### D and E. Draw experiments (lookups only, no simulation)

Pool: the valid scenarios of the oracle (baseline-violating ones removed). Every repetition draws the
whole pool in the strategy's order.

| Strategy | Order |
| --- | --- |
| Random | Uniform permutation |
| Greedy | Deterministic: repeatedly take the scenario killing most **training** mutants not yet killed; when all are killed, reset and continue; ties by scenario id |
| Grouped, equal | Cycle over the classes (random class order per round), uniform scenario within the class, without replacement |
| Grouped, weighted | Pick a class with probability proportional to its kill rate on the **training** mutants, uniform within the class, without replacement. Unlike the tick-level code, once all positive-weight classes are empty, continue with the remaining classes uniformly, so that every scenario is drawn (D10) |

Groupings: project TSC, static-distance TSC, and each tree (oracle x leaf bound).

Measures per scoring mutant and repetition:

- Time to kill = 1 + the smallest position of any scenario that kills it. Compute it as a lookup
  (position array indexed by scenario, minimum over the mutant's killing scenarios), not by drawing
  step by step.
- Suite size curve = number of scoring mutants with time to kill <= n, for n in
  `NEXT_TICK_SUITE_SIZES`.

Report median and spread over the 100 repetitions (greedy has one order). Acceptance: for the random
strategy the mean time to kill of each mutant matches (N + 1) / (K + 1), with N valid scenarios and
K killing scenarios, within sampling error.

Outputs per run: CSV of time to kill per (strategy, repetition, mutant) and CSV of the suite-size
curves, under `postEvaluation/start_scenario_draws/run_<id>/`.

## 5. Experiment conditions

| Condition | Training data | Scoring mutants | Status |
| --- | --- | --- | --- |
| Full run | All pairs of all mutants | Same mutants | Decided (D6, D7); run first |
| Fewer data, random pairs | Share p of the training mutants' pairs, drawn independently per pair | Held-out mutants | D5; held-out rule is P1 |
| Fewer data, random scenarios | Share p of the scenarios, all training mutants' pairs on them | Held-out mutants | D5; P1 |
| Fewer mutants, random | Share q of the mutants, all their scenarios | The other mutants | D5 (supervisor: the weaker condition) |
| Fewer mutants, by operator | All mutants except one operator | That operator's mutants | D5 |

Held-out split: 30%, stratified by operator, seeds 0 to n-1. Pre-study grid (P2), to adjust after the
full run's runtime is known:

| Condition | Levels | Repeats |
| --- | --- | --- |
| Random pairs | p = 50%, 10%, 1% | 3 |
| Random scenarios | p = 50%, 10%, 1% | 3 |
| Random mutants | q = 70%, 30% | 3 |
| By operator | leave out each of the 3 operators | 1 each |

Each condition runs all three oracles and all leaf bounds. Estimated cost per run with collapsed rows:
3 oracles x 4 bounds x 200 trials x 5 folds = 12,000 single-tree fits, about 80 minutes on one core
(about 0.4 s per fit, extrapolated from a synthetic benchmark, not measured on real data). The full
run replaces this estimate with measured times.

## 6. Rules for implementers

- The scoring mutants of a condition must never influence the tree, its hyperparameters, the greedy
  order or the class weights. The full run is the only deliberate exception.
- Do not label ticks, and do not use the six other monitors or the neighbouring-lane values in an
  oracle.
- Keep thresholds, leaf bounds, shares, seeds and the rear-vehicle mode in a config file; write the
  config and the git commit into every run's metadata.
- Log wall-clock time per stage in every run; the supervisor asked how many runs fit into 24 hours.
- Leave the old tick-level pipeline untouched.

## 7. Later: highway traffic (not now)

Deferred until the results above exist (D8). Idea as discussed: let vehicles drive in normal
highway traffic with the unmutated autopilot as ego; classify the ticks with the trees and TSCs to get
their distribution; draw random ticks per class and use each as a new starting scenario for all
mutants (4 s); use these to learn a better tree or refine the existing one. Note that the current
highway table (`highway_traffic_scenarios`) stores lane, position, speed and TSC class per vehicle and
tick, but not the TTC and TG values the trees need; these must be recorded or recomputed.
