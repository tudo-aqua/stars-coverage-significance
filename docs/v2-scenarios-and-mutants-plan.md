# Rare-kill mutants and the speed-grid scenario set: plan

Status: decided with Till on 2026-11-26 (D15 to D21). This plan extends `docs/test-bed-plan.md`. Values marked
"Proposed" are defaults: implement them as parameters and log the value used. Section 6 lists the order of work.

## 1. Problem

With the accident oracle (G0), the 174 mutants of the first experiment fall into three groups. Drawing scenarios at
random already kills the frequent group within a few draws, so a comparison with grouped drawing says little.

| Group | Mutant numbers | Share of scenarios with an accident | Code site in `Autopilot.kt` |
| --- | --- | --- | --- |
| Frequent (12) | 6, 111, 115, 128, 132, 147, 152, 157 | 17 to 29% | final speed command (`clampSpeedWithAccelLimits`) |
| | 9, 18, 146, 154 | 18 to 29% | lane index `baseLaneIndex + chosenDir` and `chooseDirection` |
| Rare (6) | 12, 13, 33, 34, 164, 167 | 0.03 to 0.1% | the side-corridor check `areAllLanesOnSideFree`, or how it is combined with `isTargetDirectionFree` |
| Never (156) | all others | 0 | sites where a second computation covers the same hazard |

Source: `postEvaluation/kill_matrices/kill_rarity.json`. That file lists database ids. Id 1 is the baseline, and
ids 2 and up follow the iteration order of `AutopilotMutants.byIndex`.

Target (D15): every mutant has an accident in at most 0.001% of the scenarios.

## 2. Findings

1. **Single points of failure.** The ego runs with SUMO speed mode 0 and lane-change mode 0, so SUMO never corrects
   it. The final speed command and the lane index have no second computation behind them, so a single fault there
   crashes in about a fifth of all scenarios. Where the code is redundant, a fault either never crashes or crashes
   rarely. The six rare mutants each weaken one of two lane-change checks while the other check still works. This is
   the pattern the Autopilot v2 should follow (roadmap item 5).
2. **The old grid is coarse.** Every scenario started with the same 55 m gaps and three speeds. A changed threshold,
   such as a rear gap of 13.5 m instead of 15 m, affects either no scenario or a whole block of grid cells. Rare kills
   need scenarios that differ continuously in gaps and closing speeds.
3. **MutantKraken's `LiteralChangeOperator`** replaces every Double with a uniform value in [0, 1) and every Int with
   a random 32-bit value. It also mutates the last child of the literal's parent instead of the literal itself, so it
   skips, for example, `2.0 * bLead`, `return 0.0` and function arguments.
4. **MutantKraken has no extension mechanism.** The operators are a closed Rust enum. The config file only selects
   from the 15 built-in names, and an unknown name silently resets the whole config to the defaults (all 15
   operators). New operators therefore need a fork (D19).
5. **Runs simulate much longer than they are evaluated.** The simulation loop ends only when the ego leaves the
   2,000 m road (about 68 s), but only the first 4 s are kept. Stopping at the horizon saves about 17× in simulation
   steps. This is an estimate from the code, not a measurement.
6. **The old seeder deleted results.** When the stored scenario count differed from the generated one, it cleared
   `scenario_starting_configurations`, and `ON DELETE CASCADE` then removed every row of `metric_failed_monitors`.
7. **Background vehicles keep their start speed.** The collector sets their speed at placement and never releases it,
   so they can brake but not accelerate. A driver profile therefore changes lane changing and following distance, not
   acceleration.

## 3. Decisions

| # | Decision | Agreed |
| --- | --- | --- |
| D15 | Every mutant has a G0 kill share of at most 0.001% of the scenarios | Till, 2026-11-26 |
| D16 | 10 to 20 times more scenarios; the number of mutants grows by the remaining storage factor | Till, 2026-11-26 |
| D17 | Only (scenario, mutant) outcomes are stored, not ticks | Till, 2026-11-26 |
| D18 | An unmutated vehicle layer (physical limits only) and a layered, fully mutable safety design in `Autopilot.kt` | Till, 2026-11-26 |
| D19 | New mutation operators go into a fork of MutantKraken; the fork stays local for now | Till, 2026-11-26 |
| D20 | Speed grid with position jitter and driver profiles (section 4) | Till, 2026-11-26 |
| D21 | No legacy and new code side by side: the speed grid replaces the old enumeration and reuses the existing names and types. The database is rebuilt from scratch; no old data are used | Till, 2026-11-26 |

New literal operators (D19):

- **`LiteralOffByOneOperator`.** An Int becomes n−1 and n+1. For a Double, the last written digit changes by one:
  `27.77` becomes `27.76` and `27.78`, `15.0` becomes `14.9` and `15.1`. A config option switches Doubles to ±1.0.
- **`LiteralScaleOperator`.** Each literal is multiplied by (1 − p/100) and by (1 + p/100) for every configured
  percentage p. The default is p = 10.

## 4. Scenario set

### 4.1 Size

The grid has nine cells: three lanes in each of the rows behind, beside and ahead of the ego. The ego sits in the
middle row; each of the other eight cells is empty or holds one vehicle.

The old grid:

| Step | Options | Running total |
| --- | --- | --- |
| Ego lane | 3 | 3 |
| Each of the 8 other cells: empty, calm, normal or speedy | 4⁸ = 65,536 | 196,608 |
| Position in the cell | 1 (centre) | 196,608 |

In general, with E ego speeds and S speeds for the other vehicles, the count is **3 × E × (1 + S)⁸**. The exponent 8
makes every extra speed per cell expensive:

| S | (1 + S)⁸ | E = 1 | E = 3 | E = 9 |
| --- | --- | --- | --- | --- |
| 3 | 65,536 | 196,608 | 589,824 | 1,769,472 |
| 4 | 390,625 | 1,171,875 | **3,515,625** | 10,546,875 |
| 5 | 1,679,616 | 5,038,848 | 15,116,544 | 45,349,632 |
| 9 | 100,000,000 | 300,000,000 | 900,000,000 | 2,700,000,000 |

Enumerating positions is out of reach as well. With P positions per cell, each cell has 1 + S·P states. Already
with S = 4 and P = 3 that gives 9 × 13⁸ ≈ 7.3 × 10⁹ scenarios. Positions are therefore drawn at random, not
enumerated.

### 4.2 Design

- **Speeds** (in km/h).
  - Ego: 80, 100 and 120 (E = 3).
  - Other vehicles: 70, 90, 110 and 130 (S = 4).
  - The two lists interleave, so the speed difference to the ego is always ±10, ±30 or ±50 km/h. All speeds are at
    least 60 km/h, the minimum design speed for the Autobahn (§18 StVO).
- **Enumeration.** Every combination of ego lane, ego speed and cell content is a configuration:
  3 × 3 × 5⁸ = 3,515,625 configurations, about 17.9 times the old grid.
- **Driver profiles.** The vehicle type follows from the speed:

  | Speed (km/h) | Type | vType | Driving style |
  | --- | --- | --- | --- |
  | 70 | `CALM` | `car_calm` | careful |
  | 90 | `NORMAL` | `car_normal` | normal |
  | 110 | `SPORTY` | `car_sporty` | sporty |
  | 130 | `SPEEDY` | `car_speedy` | careless |

  `car_calm`, `car_normal` and `car_speedy` take the following-distance and lane-changing values of the same-named
  types in `sumoData/highwayTraffic/vTypesHighway.add.xml`. `car_sporty` lies halfway between normal and speedy.
  The speed factor is 1 with no spread, so no hidden random factor enters the start state.
- **Maximum speed.** Each vehicle's own maximum speed is its start speed + 10 km/h, the same relation as in the old
  types.
- **Positions.** Each vehicle except the ego is placed uniformly inside its cell (behind: 0 to 100 m, beside: 100 to
  120 m, ahead: 120 to 220 m), rounded to 0.1 m. The ego stays at 110 m. Each draw is seeded from the configuration
  and the draw index, so the scenario set can be regenerated exactly.
- **Start conditions.** Between consecutive vehicles on a lane (the ego included), the bumper gap must be at least
  10 m and the time to collision at least 4 s; otherwise the lane is drawn again. With the speeds above the worst
  closing speed is 60 km/h, which needs a gap of at least 67 m. That always fits into the 100 m cells.
- **Position draws per configuration (J).** J defaults to 1. Draw j of a configuration is redrawn if it equals an
  earlier draw of the same configuration. The nine configurations without other vehicles exist only once.

  | J | Scenarios |
  | --- | --- |
  | 1 | 3,515,625 |
  | 2 | 7,031,241 |
  | 3 | 10,546,857 |

  Raise J only after the benchmark (roadmap item 4).

### 4.3 Storage

The scenario index is `enumerationIndex = j · N₀ + c`, with configuration c and N₀ = 3,515,625 configurations. Each
row stores, per cell, the vehicle type (`null` for an empty cell), the position and the speed, plus
`enumerationIndex` and `positionDrawIndex`. A fresh table is filled in index order, so the first N₀ rows hold every
configuration once. The seeder never clears the table. When the table is not empty, it resumes after the last stored
index.

## 5. Proposed defaults

| Parameter | Default | Where |
| --- | --- | --- |
| Ego speeds | 80, 100, 120 km/h | `--egoSpeeds` |
| Other speeds | 70, 90, 110, 130 km/h | `--otherSpeeds` |
| Type per speed | 70:CALM, 90:NORMAL, 110:SPORTY, 130:SPEEDY | `--types` |
| Position draws per configuration J | 1 | `--positionDraws` |
| Position jitter | on | `--variance` |
| Minimum start gap | 10 m | `--minStartGap` |
| Minimum start TTC | 4 s | `--minStartTtc` |
| Maximum speed above start speed | 10 km/h | `MAX_SPEED_ABOVE_START_SPEED_KMH` |
| `car_sporty` | tau 0.95, minGap 2.35, sigma 0.525, lcKeepRight 1.75, lcStrategic 1.25, lcCooperative 0.875, lcSpeedGain 1.125, lcAssertive 1.125 | `sumoData/experiment/vTypes.add.xml` |
| Literal scale percentages | 10 | MutantKraken config `literal_scale_percents` |
| Off-by-one for Doubles | last written digit | MutantKraken config `literal_off_by_one_mode` |
| Screening target | kill share ≤ 1e-5 | roadmap item 7 |
| Screening samples and test | 20,000 and 200,000 scenarios; one-sided binomial test, α = 0.01 | roadmap item 7 |

## 6. Roadmap

The items are listed in order. Items 2 and 3 do not depend on each other.

| # | Item | Depends on | Acceptance |
| --- | --- | --- | --- |
| 1 | Rebuild the database from scratch; the seeder never clears a table | – | Seeding a non-empty table resumes or fails, never deletes |
| 2 | Speed-grid seeding with jitter and driver profiles (section 4) | 1 | 3,515,625 rows for the defaults; the same seed gives the same rows; all start conditions hold |
| 3 | MutantKraken fork with `LiteralOffByOneOperator`, `LiteralScaleOperator` and four fixes (section 7) | – | Tests pass in Docker; generated mutants compile; two runs give the same registry |
| 4 | Runtime: stop each run at the 4 s horizon, store one outcome row per run (tick modes `ALL`, `TICK0`, `NONE`), record controller errors instead of stopping the worker, make the chunk size a parameter, benchmark | 2 | Same outcomes as a full-length run on a sample; time per run measured and written to `NOTES.md` |
| 5 | Autopilot v2: an unmutated `VehicleActuator` that applies physical limits only, plus planner and supervisor stages in `Autopilot.kt`, each with its own queries, so that every hazard is covered by two independent checks | 4 | The baseline has no accident on a 20,000-scenario sample; the twelve frequent fault patterns, applied by hand to the v2 code, are measured |
| 6 | Generate the mutants from Autopilot v2 with the fork; give `AiPilot` a reserved index (175 collides with more than 174 mutants) | 3, 5 | All mutants compile; the registry is deterministic |
| 7 | Pilot run and screening: run all mutants on 20,000 and then 200,000 sampled scenarios; drop a mutant when a one-sided binomial test rejects "kill share ≤ 1e-5" at α = 0.01 (at least 3 or at least 7 kills); report per operator | 4, 6 | Enough rare, killable mutants per operator for the "by operator" condition (D5); otherwise return to item 5 |
| 8 | Full run, then steps A to E of `docs/test-bed-plan.md` | 7 | As in `docs/test-bed-plan.md`; thresholds and TSC class counts are measured again |

## 7. MutantKraken fork

The fork is based on `JosueMolinaMorales/mutant-kraken` v0.1.6 (MIT). It lives next to this repository in
`../mutant-kraken`, on branch `stars-operators`, and is not pushed. `Dockerfile_Mutation` builds it from that
directory.

- **Both new operators** act on Int, Long and Double literals wherever they occur. They keep the source suffixes and
  wrap negative results in parentheses.
- **The fixes:**
  - The remove operators delete the token instead of writing the text `RemoveOperator`.
  - The mutants are written in a fixed order.
  - `mutations.json` contains each mutant's id and file name.
  - An unknown operator name stops the tool instead of falling back to all operators.
- **In this repository,** `scripts/create_mutants.sh` maps each mutant file through `mutations.json`, and
  `mutantkraken.config.json` mutates only `Autopilot.kt`.
