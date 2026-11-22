# Notes gathered during the experiments

## Summary

- Starting configurations are EXACT for all mutants ([details](#starting-configurations))
- Threshold sweep: the baseline never goes below 6.5 s TTC, so the pool-loss rule cannot set the
  TTC threshold. Chosen: TTC 1.5 s, TG 0.8 s front and 0.5 s rear, rear only after an ego lane
  change ([details](#threshold-sweep-for-the-ttc-and-tg-oracles))
- The first `scenario_mutant_outcomes` build used a 20 ms lane-change window instead of 20 ticks,
  so the rear "after lane change" values and the TG rear result below must be redone
  ([details](#lane-change-window-bug-found-21-november-2026))

## Starting Configurations

In all 196,608 starting scenarios, every mutant starts from the EXACT same state: the same
vehicles, each on the same lane, at the same front and back bumper positions, with the same speed,
and at the same first tick. Values are compared exactly, without rounding. Acceleration and vehicle
type are not compared: the first tick's acceleration already reflects the (possibly mutated) ego's
first decision, and the ego's type is `ego` or `mutant` depending on the run.

To check this again, run the script `scripts/check_starting_scenarios.py` in the evaluation
container on the server. This is the result from 6 November 2026:

```
python3 scripts/check_starting_scenarios.py
```

```
======================================================================
Starting ticks checked:     34,603,008 (of up to 34,603,008 combinations)
Scenarios checked:          196,608
Scenarios with deviations:  0
Report written to starting_scenario_check.json (4.8 min)
```

Run metadata (no options were passed, so all options are at their defaults):

| Setting                     | Value                                                                                    |
|-----------------------------|------------------------------------------------------------------------------------------|
| Script version              | commit `329634b`                                                                         |
| Database                    | `postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars` (hard-coded)         |
| Compared fields per vehicle | `lane`, `front`, `back`, `speed` (plus vehicle set and first tick number)                |
| `--tsc-id`                  | all (the database contains 1 TSC, id 1)                                                  |
| `--run-id`                  | all (the database contains 1 evaluation run, id 1)                                       |
| `--decimals`                | none (exact comparison)                                                                  |
| `--workers`                 | half the CPU cores of the machine                                                        |
| `--chunk-size`              | 64 scenarios per worker query                                                            |
| `--max-scenarios`           | none (all 196,608 scenarios)                                                             |
| `--max-printed`             | 20                                                                                       |
| `--max-members`             | 10                                                                                       |
| `--json-output`             | `starting_scenario_check.json`                                                           |
| Data size                   | 196,608 scenarios x 176 mutants x 1 TSC x 1 run = 34,603,008 starting ticks, all present |

## Threshold sweep for the TTC and TG oracles

Step A.2a of `docs/test-bed-plan.md`, run on 18 November 2026 (commit `4592f03`, default options,
`max_pool_loss = 0.05`). Input: `scenario_mutant_outcomes`, complete (196,608 scenarios x 176
controllers = 34,603,008 rows). Results in `postEvaluation/threshold_sweep/` (`sweep.csv`,
`chosen.json`); the run took about 21 s.

```
python3 scripts/threshold_sweep.py
```

This run used the Python version of the sweep (and the SQL script for the table). Both were later
ported to Kotlin with the same logic: `./gradlew buildScenarioMutantOutcomes --args="--scope=all"`
and `./gradlew runThresholdSweep`. The Kotlin sweep reads the table directly instead of a Parquet
cache.

- **Baseline:** no accidents. Its smallest front and rear TTC over all runs is 6.5 s, the value at
  tick 0 (the start gap is about 55 m in every scenario). So the baseline violates no TTC threshold
  in the grid (1 to 4 s), and the rule simply takes the top of the grid (4.0 s). The rule does not
  decide the TTC threshold; any value below 6.5 s removes no scenario.
- **TG front:** the baseline violations all follow a baseline lane change. 0.7 s removes 3.4%;
  0.9 to 1.2 s remove 5.09% (just above the limit), so 0.7 s is chosen. The pool loss rises in
  steps, not smoothly.
- **TG rear:** `always` at 0.5 s removes 1.2%, and only 54 of those 2,421 scenarios follow a
  baseline lane change; at 0.7 s it jumps to 8.9%. `after_lane_change` removes nothing at 0.5 s
  and 6.5% at 0.7 s; the baseline's smallest rear TG after its own lane change is 0.64 s (it
  checks only 15 m behind). 0.5 s is chosen in both modes.
- **Combined (front 0.7 s, rear 0.5 s):** pool loss 4.6% (`always`) or 3.4% (`after_lane_change`).
- **Mutants (descriptive only):** 18 mutants are killed by G0. For TTC, 9 are killed by front
  1.5 s, and 34 to 36 by front and rear 4.0 s combined. For TG, 76 (`after_lane_change`) to 96
  (`always`) are killed at front 0.7 s and rear 0.5 s. No mutant is killed in more than 90% of
  the scenarios. The Jaccard overlap of the kill pairs with G0 is 0.30 for TTC front 1.0 to 1.5 s,
  0.15 to 0.26 for the TG combinations, and about 0.002 for the rear TG after a lane change.
- AiPilot (outside check, not in the mutant numbers) has 9,392 accidents.
- **Chosen (Till, 18 November 2026; D12 to D14 in the plan):** TTC 1.5 s front and rear, TG 0.8 s
  front and 0.5 s rear, rear only after an ego lane change. The 0.8 s value is between the grid
  points, so it was checked separately on the cached table. The baseline's front-TG violations
  between 0.7 and 0.9 s sit at two values: 0.795 s (596 scenarios) and 0.808 s (2,766 scenarios).
  So 0.8 s removes 3.7% of scenarios, and anything from 0.81 s to 0.9 s removes 5.1%. With rear
  0.5 s after a lane change, TG kills 100 mutants at 0.8 s, against 76 at 0.7 s and 93 at 0.9 s
  (0.9 s removes more scenarios from the pool).

## Lane-change window bug (found 21 November 2026)

`metric_failed_monitors.tick` holds the tick time in milliseconds (0, 100, ..., 4000), not a tick
index. The outcome build compared `tick - lane-change tick <= 20`, so the D14 window ("20 ticks
after an ego lane change", 2 s) was really 20 ms: only the lane-change tick itself entered
`min_rear_ttc_after_lc` and `min_rear_tg_after_lc`. G0 and every front or `always` column are
unaffected. Affected: the TTC and TG oracles in `after_lane_change` mode, the sweep's
`after_lane_change` rows, and the D13 finding "the baseline never goes below 0.64 s after its own
lane change". The build now counts rows of the run (`ROW_NUMBER`). To redo, in this order:

```
./gradlew buildScenarioMutantOutcomes --args="--scope=all"
./gradlew runThresholdSweep
./gradlew runKillMatrices
```

Check: `--scope=baseline --laneChangeWindowTicks=0` reproduces the old after-lane-change values;
with 20 ticks they can only be smaller or equal.


#
The page works with your real results: all six runs load without errors and the charts read well, even for rare G0, where greedy needs around 100,000 draws. Open postEvaluation/start_scenario_draws/index.html and choose that folder.

First reading of the numbers

"Speedup" is how many times fewer draws than random a method needs to kill a scoring mutant, averaged over the mutants; ×1 means as fast as random.

┌───────────────────────────────┬───────────┬────────────────────────────────┬────────┐
│              Run              │ Best tree │ Static-distance TSC (weighted) │ Greedy │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Full G0                       │ ×1.22     │ ×0.98                          │ ×7.3   │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Full TTC                      │ ×2.37     │ ×1.67                          │ ×32    │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Full TG                       │ ×1.54     │ ×1.08                          │ ×18    │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Common → rare G0 (2 mutants)  │ ×0.80     │ ×0.08                          │ ×0.02  │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Common → rare TTC (4 mutants) │ ×1.47     │ ×1.18                          │ ×0.03  │
├───────────────────────────────┼───────────┼────────────────────────────────┼────────┤
│ Common → rare TG (9 mutants)  │ ×1.06     │ ×0.35                          │ ×2.2   │
└───────────────────────────────┴───────────┴────────────────────────────────┴────────┘

Full run (trained and scored on the same mutants):
- The trees beat random modestly and the static TSC slightly. The project TSC is slower than random for G0 and TG.
- Greedy is far ahead, but it is fitted to exactly these mutants, so it is an upper bound, not a fair competitor.
- Picking groups by kill rate ("weighted") is clearly better than cycling over them ("equal"), which is often slower than random.
- With or without ego_lane, and the leaf bound, make no consistent difference.
- For G0 and TG there is little room to improve: random drawing kills most mutants within 2–20 draws. In G0, 10 of 12 mutants die within 4 draws; only 135 and 147 are slow.

Common → rare (trained on common mutants, scored on rare ones): the trees don't carry over.
- G0: every grouping is slower than random, and the bigger the tree, the worse: ×0.80 with ≤ 16 leaves, ×0.12 with ≤ 512.
- TG: about the same as random.
- TTC: a small gain, but over only 4 mutants.
- "Equal" now beats "weighted", the opposite of the full run. Weights learned on the common mutants send the draws away from where the rare ones are killed.
- Greedy collapses for G0 and TTC: it draws the scenarios that kill the common mutants first and reaches the rare mutants' scenarios very late. TG is the exception at ×2.2, and the only clear gain in this condition.

How much weight these numbers can bear

- The "Best tree" column takes the highest of 16 tree variants, so it is optimistic. A value like ×1.06 for rare TG fits "no effect".
- Rare G0 rests on 2 mutants and rare TTC on 4, so one mutant can change the result.
- The average treats easy and rare mutants equally. In the full run this caps the possible speedups, because random already kills the easy mutants within 2–4 draws.

The page and the .gitignore change are still uncommitted. One addition to the "All runs" table that would counter the best-of-16 optimism: a column with the median over the 16 tree variants.
