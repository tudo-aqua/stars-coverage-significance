# Notes gathered during the experiments

## Summary

- Starting configurations are EXACT for all mutants ([details](#starting-configurations))

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
