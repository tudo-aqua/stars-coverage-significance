# STARS Coverage Significance — Docker Usage

#### (Optional) Git Hooks
If you want to use our proposed Git Hooks you can execute the following command:
```shell
git config --local core.hooksPath .githooks
```

This repository ships:

- A `Dockerfile` that builds a container image capable of running the Gradle task `runEvaluation`.
- A `MutantDockerfile` that builds a container image capable of mutating the autopilot.
- A `docker-compose.yml` that starts the required PostgreSQL database (`stars_db`) and PgBouncer (`stars_pgbouncer`).

## Prerequisites

- Docker Engine
- Docker Compose (the `docker compose` CLI plugin)

---

## 1) Build the evaluation image (Dockerfile)

From the directory that contains the `Dockerfile`, build an image:

```bash
docker build --no-cache -t stars-evaluation:latest .
```

---

## Entering the container interactively

After building the image, you can open an interactive shell inside a new container to inspect the environment, pull the latest code, or run tasks manually. The repository is already cloned into `/app/stars-coverage-significance` at image build time and that directory is the default working directory.

```bash
docker run -it stars-evaluation:latest /bin/bash
```

Once inside the container, pull the latest changes from the remote:

```bash
git pull
```

---

## 2) Start database + PgBouncer (docker-compose.yml)

The compose stack starts:

- PostgreSQL exposed on `localhost:5432`
- PgBouncer exposed on `localhost:6432`

### Start (detached)

```bash
docker compose -p stars up -d
```

### View status

```bash
docker compose -p stars ps
```

### View logs

```bash
docker compose -p stars logs -f
```

### Stop and remove containers (keep DB data volume)

```bash
docker compose -p stars down
```

### Stop and remove containers **and** delete the DB volume (destructive)

This deletes the `stars_pgdata` named volume (i.e., Postgres data).

```bash
docker compose -p stars down -v
```

### Recreate containers (compose)

If you only need to recreate containers (e.g., after config changes), do:

```bash
docker compose -p stars down
docker compose -p stars up -d
```

### Update the base images (Postgres / PgBouncer)

```bash
docker compose -p stars pull
docker compose -p stars up -d
```

---

## Mutating the Autopilot

The reproduction artifact already includes all mutations of the autopilot. In the case you want to mutate the autopilot yourself,
a Docker image `mutations` is provided. For the mutation, this artifact uses `mutant-kraken` which is publicly available on [GitHub](https://github.com/JosueMolinaMorales/mutant-kraken).

### Building the Mutant Image

First, build the base image:

```powershell
docker build -t mutation-base-image -f Dockerfile_Mutation .
```

### Mutating the Autopilot on Windows (PowerShell)

First, load the provided base image into your local Docker environment:

```powershell
docker load -i mutation-base-image.tar.gz
```

If your Docker installation expects an uncompressed tar archive and reports the error `invalid tar header`, use the following command instead:

```powershell
tar -xzf mutation-base-image.tar.gz
docker load -i mutation-base-image.tar
```

After loading the image, verify that it is available locally:

```powershell
docker image ls | Select-String mutation-base-image
```

The container writes all final outputs (prepared data and generated PDFs) to `/out` inside the container. To make these files available on the host system, mount a local `mutants/` directory to `/out`.
The following command should be called from the root of the repository to correctly set the working directory:

```powershell
New-Item -ItemType Directory -Force -Path ".\src\main\kotlin\tools\aqua\stars\sumo\mutants" | Out-Null
```

Finally, run the experiment.

```bash
docker run -it -v "${PWD}\src\main\kotlin\tools\aqua\stars\sumo\mutants:/out" mutation-base-image /bin/bash -lc "OUT_DIR=/out /repo/scripts/create_mutants.sh"
```

Under PowerShell:

```powershell
docker run -it -v "${PWD}\src\main\kotlin\tools\aqua\stars\sumo\mutants:/out" mutation-base-image /bin/bash -lc "sed -i 's/\r$//' /repo/scripts/create_mutants.sh && OUT_DIR=/out /repo/scripts/create_mutants.sh"
```

## Docker Base Images

We provide both the Dockerfiles (`Dockerfile` and `MutantDockerfile`) for building the base images and pre-built base images for fully offline execution.

### Building and Shipping of Experiment Base Image

Build the base image:

```bash
docker build -t base-image .
```

Save the base image to a tar archive:

```bash
docker save -o base-image.tar base-image
```

Compress the archive:

```bash
gzip -9 base-image.tar
```

This produces the compressed base image archive `base-image.tar.gz`.

### Building and Shipping of Mutation Base Image

Build the base image:

```bash
docker build -t mutation-base-image -f Dockerfile_Mutation .
```

Save the base image to a tar archive:

```bash
docker save -o mutation-base-image.tar mutation-base-image
```

Compress the archive:

```bash
gzip -9 mutation-base-image.tar
```

This produces the compressed base image archive `mutation-base-image.tar.gz`.

## 3) Run the evaluation container against the compose stack

### Ensure compose is running first

```bash
docker compose -p stars up -d
```

### Run the evaluation

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runEvaluation
```

`RunEvaluation.kt` parses `--bufferProcessors` from `main(args)`, so you can pass it via the Gradle `--args` flag:

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runEvaluation --args="--bufferProcessors=2"
```

### Run the highway analysis

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runHighwayAnalysis
```

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runHighwayAnalysis --args="--bufferProcessors=2"
```

### Run the post-evaluation

```bash
docker run --name stars-coverage-significance-post-evaluation stars-evaluation:latest ./gradlew --no-daemon runPostEvaluation
```

### Run the baseline next-tick evaluation

`RunBaselineNextTick.kt` optionally parses a `decisionTreeRunId` (the `decision_tree_runs.id` to use for leaf assignments) as the first `main(args)` entry. When omitted, it falls back to the latest full run (no test set at all, `n_test_mutants = 0`). Pass it via the Gradle `--args` flag:

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runBaselineNextTick --args="8"
```

### Run the draw-ticks-with-decision-tree-grouping evaluation

`RunDrawTicksWithDecisionTreeGrouping.kt` samples individual ticks directly (rather than whole starting scenarios, contrast with the baseline next-tick evaluation above) under four sampling strategies — uniform-random, DC-leaf round-robin ("equal"), DC-leaf significance-weighted, and DC-leaf alternating (alternates draw-by-draw between the round-robin and weighted policies, falling back to whichever still has ticks left if the other is exhausted) — and writes three kinds of results, each scoped under its own `postEvaluation/draw_ticks_with_decision_tree_grouping/run_<runId>/` folder (`runId` being the decision tree run whose leaf assignments were used) so repeated evaluations against different runs don't overwrite each other: how many distinct mutants each strategy kills per suite size (`size_<n>/draw_ticks_<strategy>.csv`); per accident-causing mutant, how many ticks each strategy needs before first killing it (`time_to_kill/mutant_<id>/ttk_<strategy>.csv`); and the per-leaf significance data (`significance.json`) needed to compute the E(k/N)/E(equal)/E(weight)/E(alternating) estimators for that run, consumed by `postEvaluation/time_to_kill_comparison/index.html`'s expected-vs-actual comparison.

`main(args)` selects which decision tree run(s) to evaluate:

| `args` | Behavior |
|---|---|
| *(none)*, or `--latest` | The actual latest decision tree run overall (highest ID), full or split |
| `--latest-full` | The latest *full* run — no test set at all (`n_test_mutants = 0`) |
| `--latest-split` | The latest *split* run — has a test set (`n_test_mutants > 0`), whether from a random `--train-fraction` split or a manual `--mutant-ids`/`--mutant-numbers` selection |
| `--all` | Every run currently in `decision_tree_runs`, evaluated one after another (ascending ID) — multiplies runtime by the number of runs, since each run reloads the full tick table |
| a single run ID, e.g. `8` | Just that run (`decision_tree_runs.id`) |
| multiple run IDs, comma- and/or space-separated, e.g. `1,2,3` or `1 2 3` | Each in turn, in the given order |

```bash
# Actual latest run, full or split (default)
docker run stars-evaluation:latest ./gradlew --no-daemon runDrawTicksWithDecisionTreeGrouping

# Latest full run specifically
docker run stars-evaluation:latest ./gradlew --no-daemon runDrawTicksWithDecisionTreeGrouping --args="--latest-full"

# One specific run
docker run stars-evaluation:latest ./gradlew --no-daemon runDrawTicksWithDecisionTreeGrouping --args="8"

# Several specific runs, one after another
docker run stars-evaluation:latest ./gradlew --no-daemon runDrawTicksWithDecisionTreeGrouping --args="1,2,3"

# Every run in the database
docker run stars-evaluation:latest ./gradlew --no-daemon runDrawTicksWithDecisionTreeGrouping --args="--all"
```

### Run the duplicate-tick analysis

`RunAnalyzeDuplicateTicks.kt` checks how many ticks in `metric_failed_monitors` are duplicates of each other, where two ticks count as "the same" when the ego vehicle's spatial relation to its neighbours (relative bumper-to-bumper distances), the ego and neighbour speeds, and the ego and neighbour accelerations all match — see `MetricFailedMonitorsTable.buildDuplicateTickCompareColumns`. Since these values are floats, the compared columns are rounded to a decreasing number of decimal places (exact, then 6 decimals down to 0) and duplicate counts are reported at each level. Writes `postEvaluation/duplicate_ticks/duplicate_tick_groups.json` containing every group (member row IDs + rounded values) per precision level plus a summary.

This is a Kotlin reimplementation of `scripts/analyze_duplicate_ticks.py`, which kept getting silently killed on the server (almost certainly the OS OOM killer): it materialized every group at every precision level as a Python dict before serializing, multiplying per-row memory overhead by the number of precision levels. The Kotlin version processes one precision level at a time, streaming each level's groups straight to the output file and discarding them before moving to the next, so peak memory doesn't grow with the number of precision levels — use this for full-scale/server runs.

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runAnalyzeDuplicateTicks
```

### Run the tick replay analysis

`RunTickReplay.kt` takes a comma-separated list of `metric_failed_monitors.id` values, reconstructs each tick's local traffic scene in a fresh SUMO/libsumo simulation, and lets every known mutant separately take control of the ego vehicle for exactly one simulated step, to compare what each mutant actually does when faced with that exact scene.

Every vehicle recorded present at the tick is placed at its own recorded position/speed/acceleration/type, read from the `all_vehicles_json` column (`metric_failed_monitors`, a non-nullable JSON array of `TickVehicleSnapshot` populated by `FailedMonitorsMetric` for every tick written by the live evaluation pipeline) rather than from the 6 nearest-neighbour `surrounding*` columns — a vehicle "blocked" from being nearest (e.g. two cars ahead in the same lane) used to be silently missing from the reconstruction. See `tools.aqua.stars.data.sumo.libSumo.computeReplayPlacements` for the placement logic and `LibsumoDynamicDataCollector.replayTickForMutant` for the one-step replay itself.

Scope: this reports the mutant's maneuver command, the resulting next-tick kinematics, and whether a collision occurs. It does **not** re-evaluate TSC monitors (G0–G4/I1/I2) — that requires the full `TSCEvaluation` framework running across a longer window of ticks.

Writes `postEvaluation/tick_replay/tick_replay_<tick-ids>.json`, one entry per (tick, mutant) pair.

**Lead time**: a single-step replay gives a substituted mutant exactly one simulated step to react from the recorded critical moment — plenty for **ego**, since its maneuver is a direct command, but not for *background* vehicles' own autonomous SUMO lane changes, which build up over several real steps before executing (SUMO's default LC2013 model accumulates a `mySpeedGainProbability*` value across ticks before crossing a threshold — a freshly force-placed vehicle starts that at zero regardless of how favorable its instantaneous situation looks). Pass `--leadTimeSeconds=<comma separated values>` (e.g. `0.2,0.5,0.7,1.0`) to additionally reconstruct each tick from the closest available tick that many seconds *earlier* instead, stepping forward continuously through to one step past the original moment — giving that build-up a chance to happen for real. Writes one output file per lead time under `postEvaluation/tick_replay_leadtime_<value>s/`, alongside the original (omit the flag) `tick_replay/` output.

```bash
docker run stars-evaluation:latest ./gradlew --no-daemon runTickReplay --args="123,456"

# Also replay with 0.2s/0.5s/0.7s/1.0s of lead time before each tick
docker run stars-evaluation:latest ./gradlew --no-daemon runTickReplay --args="123,456 --leadTimeSeconds=0.2,0.5,0.7,1.0"
```

### Run the G0 mutant coverage replay analysis

`RunG0MutantCoverageReplay.kt` finds every `metric_failed_monitors` row whose *next* tick was recorded as a G0 (Accidents) failure (`next_tick_monitor_g0_Accidents_failed = true`), then replays each one (same scene reconstruction as the tick replay analysis above) once per known mutant to answer two questions per tick: does the mutant that originally produced it still reproduce the failure when replayed, and do any *other* mutants, substituted into that exact same recorded scene, additionally trigger a G0 failure?

Unlike the tick replay analysis, this does re-evaluate the G0 monitor on the replayed result — G0's predicate (`tools.aqua.stars.coverage.significance.tsc.g0Accidents`) is a pure single-tick collision check (no `previous`/`once` history dependence, only the top-level `globally`, which degenerates to a single-tick check on an unlinked tick), so `g0Accidents.holds(nextTick)` can be called directly on the one-step replay result without the full `TSCEvaluation` framework. This does not generalize to G1–G4/I1/I2, which need real tick history.

**Parallelism**: each replay reloads and steps a full libsumo simulation, and libsumo wraps a single global native simulation per process — the same reason `RunEvaluation.kt` spawns one JVM *process* per core instead of using threads. `RunG0MutantCoverageReplay.kt` is a coordinator that spawns one worker process per available core (`G0MutantCoverageReplayWorker`, started the same way `evaluationWorker.kt` is), each deterministically claiming every Nth flagged tick (sorted by id, no coordination needed between workers), then aggregates all workers' results once they finish.

**Output**: each worker streams one JSON object per line (NDJSON) to its own detail file as each tick finishes, flushing immediately — `postEvaluation/g0_mutant_coverage_replay/details/g0_mutant_coverage_replay_<runId-or-"all">_worker<N>.jsonl`, holding the full per-mutant `mutantResults` breakdown per tick (`g0Failed` is `null` if the replay was inconclusive because the ego left the simulation). This replaces the previous single-file, write-at-the-very-end design, which lost all progress if the process was killed before finishing a multi-hour run.

After every worker completes, the coordinator reads all detail files back and writes one aggregate summary, `postEvaluation/g0_mutant_coverage_replay/g0_mutant_coverage_replay_summary_<runId-or-"all">.json`, answering:
- Were original mutants "killed again" on replay? (`originalMutantReproducedCount` / `originalMutantNotReproducedCount` / `originalMutantInconclusiveCount`, each paired with a `*TickIds` list of the matching tick ids across every mutant combined — `mutantStats` additionally gives each mutant its own `originalTickReproducedTickIds` / `originalTickNotReproducedTickIds` / `originalTickInconclusiveTickIds`, so a specific mutant's ticks in each category can be looked up directly)
- Which ticks are essentially unavoidable — every other mutant, substituted into the same recorded scene, also fails? (`unavoidableTickCount`, `unavoidableTickIds`), plus three "almost unavoidable" tiers one step short of that — exactly 1, 2, or 3 other mutants avoided it (`almostUnavoidableTicks`, non-cumulative)
- How many mutants gained "new" kills — ticks not originally attributed to them where they also fail on replay? (`mutantsWithNewKillsCount`, plus each mutant's `newKillTickIds` in `mutantStats`)

`postEvaluation/g0_mutant_coverage_replay/index.html` is a self-contained static viewer for that summary JSON (no server or build step needed — open it directly and use the file picker to load a specific summary file, or serve the folder over HTTP for it to auto-load `g0_mutant_coverage_replay_summary_all.json`). It works unmodified for the lead-time summaries below too — the JSON shape is identical, only the source folder differs.

**Lead time**: over 200k of the ~380k flagged ticks turn out "unavoidable" or close to it (every, or all but a few, mutants also fail on replay) — see the "Lead time" section on `TickReplayAnalysis` above for why a single-step replay from the critical moment can under-report what a mutant (or the background traffic around it) would actually do given real reaction time, rather than that necessarily meaning the spawned situation itself is inescapable. Pass `--leadTimeSeconds=<comma separated values>` (e.g. `0.2,0.5,0.7,1.0`) to additionally run one full replay+aggregate pass per lead time — same worker/detail-file/aggregation machinery, just starting each flagged tick's replay from the closest available tick that many seconds earlier and stepping through to one step past the original moment. Each lead time gets its own sibling folder, `postEvaluation/g0_mutant_coverage_replay_leadtime_<value>s/`; the original (omit the flag) `g0_mutant_coverage_replay/` output is untouched. Passes run sequentially, each using full available parallelism.

```bash
# All runs, one worker per available core
docker run stars-evaluation:latest ./gradlew --no-daemon runG0MutantCoverageReplay

# Restrict to one evaluation run, reserving 2 cores for buffering
docker run stars-evaluation:latest ./gradlew --no-daemon runG0MutantCoverageReplay --args="--runId=8 --bufferProcessors=2"

# Re-run only the aggregation step against an existing details/ folder (no replay) — e.g. to
# regenerate a summary that failed to copy/parse correctly, or after a change to the aggregation
# logic itself
docker run stars-evaluation:latest ./gradlew --no-daemon runG0MutantCoverageReplay --args="--aggregateOnly=true"

# Also run with 0.2s/0.5s/0.7s/1.0s of lead time before each flagged tick (4 extra passes, 4 extra
# output folders, sequential)
docker run stars-evaluation:latest ./gradlew --no-daemon runG0MutantCoverageReplay --args="--leadTimeSeconds=0.0,0.2,0.5,0.7,1.0"
```

---

## Standalone Tools

### `tools/tick_visualizer/index.html` — Tick vehicle position visualizer

A self-contained static page (no server, build step, or Gradle task needed — just open it) that draws a top-down view of every vehicle in one tick: lanes as horizontal bands (lane 0 at the bottom, per SUMO's 0=rightmost convention), vehicles as colored rectangles sized/positioned by their `front`/`back` (m), with a shaded stripe marking the front edge. Scroll/pinch to zoom, drag to pan; hover a vehicle for its full data, or read it from the table below the scene.

Paste or upload any JSON array shaped like `all_vehicles_json` / `TickVehicleSnapshot[]` — e.g. the value of a `metric_failed_monitors.all_vehicles_json` cell, or a tick's vehicle list from the tick-replay/G0 mutant coverage detail files. Opens with a small example scene pre-filled so it's immediately usable without pasting anything first.

---

## Python Scripts

All Python dependencies are installed in the Docker image. To run the scripts outside Docker, install them first:

```bash
pip install matplotlib numpy pandas scipy lightgbm polars connectorx graphviz psycopg2-binary scikit-learn optuna
```

---

### `scripts/export_parquet.py` — Export PostgreSQL table to Parquet

Exports the `metric_failed_monitors` table from PostgreSQL to a Parquet file using parallel reads via [connectorx](https://github.com/sfu-db/connector-x). On a server with many cores this is significantly faster than a single-threaded CSV export.

Excludes the `all_vehicles_json` column by default — it's the single heaviest column (a per-row JSON array of every vehicle present at that tick, added for the tick-replay feature) and isn't read by any current consumer of this export (`decision_tree_g0.py`, `analyze_duplicate_ticks.py`). Column names are discovered at runtime via `information_schema`, so excluding it doesn't require hardcoding/maintaining the rest of the column list.

| Argument | Default | Description |
|---|---|---|
| `--uri` | *(required)* | PostgreSQL connection URI: `postgresql://user:pass@host:port/db` |
| `--output` | `metric_failed_monitors.parquet` | Output Parquet file path |
| `--partitions` | `96` | Number of parallel read partitions; set to match available CPU cores |
| `--include-all-vehicles-json` | off | Include the `all_vehicles_json` column anyway |

```bash
python3 scripts/export_parquet.py \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars \
  --output metric_failed_monitors.parquet

# Fewer partitions on a smaller machine
python3 scripts/export_parquet.py \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars \
  --output metric_failed_monitors.parquet \
  --partitions 4
```

---

### `scripts/decision_tree_g0.py` — Decision tree classifier for `next_tick_monitor_g0_Accidents_failed`

Trains a single LightGBM decision tree that predicts whether the G0 (Accidents) monitor will fail in the next tick, using ego maneuver, ego kinematics, and surrounding vehicle distances as features. Reads the Parquet file produced by `export_parquet.py`. Hyperparameters (number of leaves, max depth, min split gain, min samples per leaf) are tuned automatically via Optuna — no manual values required. Each trial fits and scores on the entire training set (no cross-validation split), so hyperparameter search always learns from all available rows. Current-tick monitor states are never used as input features — only `next_tick_monitor_g0_Accidents_failed` (the prediction target) is derived from them — to avoid leaking the current-tick value of the monitor being predicted one tick ahead.

| Argument | Default | Description |
|---|------|---|
| `parquet` | *(required)* | Path to the Parquet export of `metric_failed_monitors` |
| `--n-trials` | `50` | Number of Optuna trials for automatic hyperparameter tuning |
| `--max-leaves` | `512` | Upper bound for `num_leaves` in the Optuna search; lower values (e.g. `64`) prevent tiny leaves that appear when `--class-weight=balanced` upweights each positive sample |
| `--class-weight` | `balanced` | Class imbalance strategy: `balanced` (per-sample upweighting, `min_child_samples` applies to weighted counts) or `scale-pos-weight` (scalar loss correction, `min_child_samples` applies to raw counts — prevents tiny leaves) |
| `--n-jobs` | `48` | Total CPU threads available; set to match available cores |
| `--tuning-jobs` | `8`  | Number of Optuna trials run concurrently during tuning; `--n-jobs` threads are split evenly across them (`n-jobs / tuning-jobs` threads per trial). Since each trial fits only one tree, more concurrent trials with fewer threads each is usually faster than one trial at a time with many threads |
| `--train-fraction` | `1.0` | Fraction of unique mutant IDs used for training (`0 < F ≤ 1.0`); the remaining mutants form the test set. Left at `1.0` with no `--mutant-ids`/`--mutant-numbers`, there is no test set at all |
| `--seed` | `42` | Random seed for the mutant train/test shuffle |
| `--mutant-ids` | *(none, = every mutant in the Parquet file)* | Comma-separated raw `mutant_id` values (`metric_failed_monitors.mutant_id` / `mutants.id` — the database's own serial primary key) to restrict the run to, e.g. `1,10,11,12`. **Not** the same as a mutant's number (see `--mutant-numbers` below and "Mutant category lists"). Applied *before* the `--train-fraction`/`--seed` split — the split logic itself is unchanged, it just runs over this smaller universe instead of every mutant in the file. Additive to everything else above: combine with `--train-fraction` for a train/test split within just these mutants, or leave `--train-fraction 1.0` (default) to train on exactly these mutants and automatically test on every *other* mutant present in the Parquet file — measuring generalization to mutant categories not selected here. Combines with `--mutant-numbers` (union) if both are given |
| `--mutant-numbers` | *(none)* | Comma-separated `mutant_number` values (`mutants.mutant_number` — the human-meaningful `AutopilotMutant<N>` index, e.g. the numbers in `AutopilotMutants.kt`/README) to restrict the run to. **Requires `--uri`**, to resolve `mutant_number → mutant_id` via the `mutants` table, since the Parquet file only carries `mutant_id`. Otherwise behaves exactly like `--mutant-ids` |
| `--output` | *(none)* | Write a Graphviz `.dot` file to an explicit path (overrides the run-named file from `--out-dir`) |
| `--annotate` | *(none)* | Write a Parquet file containing features + target + `leaf_node_id` for every row |
| `--uri` | *(none)* | Record the run and write leaf assignments to the database: `postgresql://user:pass@host:port/db` |
| `--db-workers` | `48` | Parallel database connections used when writing leaf assignments via `--uri` |
| `--out-dir` | parquet's directory | Directory for run-named output files (`run_<id>.dot`, `run_<id>.log`); only used when `--uri` is set |

When `--uri` is provided the script automatically captures the full stdout log and the DOT source, stores both in the `decision_tree_runs` table, and writes `run_<id>.dot` / `run_<id>.log` to `--out-dir`.

#### Mutant category lists (`AutopilotMutants.kt`)

`AutopilotMutants.kt` groups the autopilot mutants by which mutation operator created them, in two `Set<KClass<out Mutant>>`s. These are **mutant numbers** (`mutants.mutant_number`), not raw `mutant_id`s — pass them to `--mutant-numbers` above (not `--mutant-ids`) to train one decision tree per category instead of one tree over every mutant:

| List | Mutant numbers | Composition (per `AutopilotMutants.byIndex`'s comments) |
|---|---|---|
| `arithmeticReplacementOperatorMutants` | `1,10,11,12,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125` | All `ArithmeticReplacementOperator` mutants |
| `otherReplacementOperatorMutants` | `145,146,147,149,150,152,153,154,155,156,157,158,159,160,161,162,163,164,165,167,168,169,170,171,172,174,175,176,177,178` | `LogicalReplacementOperator` and `UnaryRemovalOperator` mutants |

#### Feature groups

All feature groups are enabled by default. Disable any group with `--no-<group>`:

| Flag | Columns | Description |
|---|---|---|
| `--ego-maneuver` / `--no-ego-maneuver` | 2 | Ego planned maneuver: `ego_maneuver_speed`, `ego_maneuver_lane_change` |
| `--ego-speed` / `--no-ego-speed` | 1 | Ego speed: `ego_speed_mps` |
| `--ego-accel` / `--no-ego-accel` | 1 | Ego acceleration: `ego_accel_mps2` |
| `--ego-position` / `--no-ego-position` | 2 | Ego lane position: `ego_front_bumper_pos_meters`, `ego_back_bumper_pos_meters` |
| `--distances` / `--no-distances` | 8 | Bumper-to-bumper distance to nearest neighbour per grid cell (`surrounding_dist_*`) |
| `--neighbor-kinematics` / `--no-neighbor-kinematics` | 48 | Per-neighbour speed, acceleration, bumper positions, and diffs |
| `--time-gaps` / `--no-time-gaps` | 16 | Per-neighbour time-to-collision (`*_ttc_s`) and time gap (`*_tg_s`) |

```bash
# Basic run — all feature groups, no DB write (50 Optuna trials for tuning)
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet --output tree.dot

# More thorough tuning with 200 trials
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
  --n-trials 200 \
  --no-ego-maneuver \
  --no-ego-position \
  --no-ego-accel \
  --no-distances \
  --no-neighbor-kinematics \
  --class-weight balanced \
  --max-leaves 512 \
  --out-dir /results/runs \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
  --db-workers 48 \
  --train-fraction 0.5 \
  --seed 43

# Train/test split: 80 % of mutants for training, rest for evaluation
# Run is recorded in the DB; run_<id>.dot and run_<id>.log are written automatically
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
  --train-fraction 0.8 \
  --seed 42 \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
  --db-workers 48 \
  --out-dir /results/runs

# Focus on time gaps only — disable raw distances and per-neighbour kinematics
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
  --train-fraction 0.5 \
  --seed 4 \
  --no-ego-maneuver \
  --no-ego-position \
  --no-ego-accel \
  --no-distances \
  --no-neighbor-kinematics \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
  --db-workers 48 \
  --out-dir ./results/runs

# Render a stored dot file to PNG
dot -Tpng run_42.dot -o run_42.png

# Train one decision tree per mutant-operator category (see "Mutant category lists" above),
# one after another in a single invocation — each --uri call records its own run in the DB,
# so afterwards you have two separate decision_tree_runs rows/DOT files/logs to compare.
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
  --n-trials 200 \
  --no-ego-maneuver \
  --no-ego-position \
  --no-ego-accel \
  --no-distances \
  --no-neighbor-kinematics \
  --class-weight balanced \
  --max-leaves 512 \
  --out-dir /results/runs \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
  --db-workers 48 \
  --mutant-numbers 1,10,11,12,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125 \
  --out-dir /results/runs \
&& python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
  --n-trials 200 \
  --no-ego-maneuver \
  --no-ego-position \
  --no-ego-accel \
  --no-distances \
  --no-neighbor-kinematics \
  --class-weight balanced \
  --max-leaves 512 \
  --out-dir /results/runs \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
  --db-workers 48 \
  --mutant-numbers 145,146,147,149,150,152,153,154,155,156,157,158,159,160,161,162,163,164,165,167,168,169,170,171,172,174,175,176,177,178 \
  --out-dir /results/runs
```

---

### `scripts/analyze_duplicate_ticks.py` — Analyze duplicate ticks in `metric_failed_monitors`

> **On the full server-scale table, prefer `runAnalyzeDuplicateTicks`** (see [Run the duplicate-tick analysis](#run-the-duplicate-tick-analysis)) — this script was observed getting silently killed on the server (no error, just terminated), almost certainly by the OS OOM killer, because it materializes every group at every precision level as a Python dict before writing JSON. It's still fine for smaller/local Parquet files.

Checks how many ticks in `metric_failed_monitors` are duplicates of each other, where two ticks count as "the same" when the ego vehicle's spatial relation to its neighbours (relative bumper-to-bumper distances), the ego and neighbour speeds, and the ego and neighbour accelerations all match. Absolute lane positions and monitor/target columns are excluded. Since these values are stored as floats, exact equality rarely holds for semantically identical scenes, so the script rounds the compared columns to a decreasing number of decimal places (starting exact, then 6 decimals down to 0) and reports duplicate counts at each level. Reads either a Parquet export or connects to PostgreSQL directly for just the needed columns.

| Argument | Default | Description |
|---|---|---|
| `--parquet` | *(one of `--parquet`/`--uri` required)* | Path to a Parquet export of `metric_failed_monitors` |
| `--uri` | *(one of `--parquet`/`--uri` required)* | PostgreSQL connection URI: `postgresql://user:pass@host:port/db` |
| `--json-output` | `duplicate_tick_groups.json` | Path to write every group (row IDs + rounded values) per precision level as JSON |

```bash
python3 scripts/analyze_duplicate_ticks.py --parquet metric_failed_monitors.parquet \
  --json-output duplicate_tick_groups.json

python3 scripts/analyze_duplicate_ticks.py \
  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars \
  --json-output duplicate_tick_groups.json
```

---

### `postEvaluation/` — Plot scripts

Each script reads CSV files from its own directory and writes PNG and PDF plots alongside them.

#### `tsc_instance_change_analysis/tsc_instance_change_barplot.py`

Histogram of time-until-first-TSC-instance-change. Reads all `*.csv` files in its directory.

```bash
python postEvaluation/tsc_instance_change_analysis/tsc_instance_change_barplot.py
```

#### `baseline_next_tick/baseline_next_tick_scatter.py` and `baseline_next_tick/baseline_next_tick_scatter.py`

Scatter plots of mutants killed from randomly drawing vs TSC-based vs learned Decision tree.

```bash
python3 postEvaluation/baseline_next_tick/baseline_next_tick_scatter.py
python3 postEvaluation/baseline_next_tick/baseline_next_tick_boxplot.py
python3 postEvaluation/baseline_next_tick/baseline_next_tick_combined.py
```

#### `baseline/baseline_scatter.py` and `baseline_with_monitors/baseline_scatter.py`

Scatter plots of mutants killed vs. TSC classes covered. Reads all `*.csv` files in the respective directory.

```bash
python3 postEvaluation/baseline/baseline_scatter.py
python3 postEvaluation/baseline_with_monitors/baseline_scatter.py
```

#### `count_of_mutants_killed_per_monitor/countOfMutantsKilledPerMonitor.py`

Bar chart of mutant kill counts per monitor. Reads `countOfMutantsKilledPerMonitor.csv`.

```bash
python postEvaluation/count_of_mutants_killed_per_monitor/countOfMutantsKilledPerMonitor.py
```

#### `count_of_scenarios_killing_mutant_per_mutant/countOfMutantsKilledPerMonitor.py`

Scatter plot of how many scenarios kill each mutant. Reads `countOfScenariosKillingMutantPerMutant.csv`.

```bash
python postEvaluation/count_of_scenarios_killing_mutant_per_mutant/countOfMutantsKilledPerMonitor.py
```

#### `highway_traffic_analysis/highwayTrafficAnalysis.py`

Produces three plots (linear/linear, linear/log, log/log) of scenario frequency and prints exponential fit statistics. Reads `highwayTrafficAnalysisValues.csv` by default; pass a path as the first argument to override.

```bash
python postEvaluation/highway_traffic_analysis/highwayTrafficAnalysis.py
python postEvaluation/highway_traffic_analysis/highwayTrafficAnalysis.py path/to/data.csv
```

#### `mutant_killing/countOfKilledMutants_scatter.py`

Scatter plot of killed mutants per TSC coverage class, one plot per sample size. Reads subdirectories named `countOfKilledMutants_<sampleSize>/`.

```bash
python postEvaluation/mutant_killing/countOfKilledMutants_scatter.py
```

#### `mutant_killing/countOfMutantsKilledWithMonitors_scatter.py`

Same as above but includes monitor breakdown. Reads subdirectories named `countOfMutantsKilledWithMonitors_<sampleSize>/`.

```bash
python postEvaluation/mutant_killing/countOfMutantsKilledWithMonitors_scatter.py
```

#### `mutants_killed_by_monitors_per_scenario/mutantsKilledByMonitorPerScenario.py`

Combined bar + scatter plot of long-tail scenario frequency and mutant kill counts per monitor. Reads `mutantsKilledByMonitorsPerScenario.csv`. Produces one plot per monitor and one combined plot.

```bash
python postEvaluation/mutants_killed_by_monitors_per_scenario/mutantsKilledByMonitorPerScenario.py
```

#### `scenario_by_monitor_cross_table/scenarioByMonitorCrossTable.py`

Black-and-white heatmap of scenarios (x-axis) vs. mutants (y-axis), one plot per monitor CSV. Reads all `*.csv` files in its directory.

```bash
python postEvaluation/scenario_by_monitor_cross_table/scenarioByMonitorCrossTable.py
```

#### `scenario_by_scenario_cross_table/scenarioByScenarioCrossTable.py`

Greyscale heatmap of the scenario-by-scenario similarity matrix. Reads `scenario_by_scenario_cross_table.csv`.

```bash
python postEvaluation/scenario_by_scenario_cross_table/scenarioByScenarioCrossTable.py
```

---

### `sumoData/fcdReplay/fcdReplay.py` — FCD replay in SUMO-GUI

Replays an FCD output file as animated oriented bounding boxes on top of a running SUMO simulation. Requires SUMO to be installed and `SUMO_HOME` to be set.

| Argument | Default | Description |
|---|---|---|
| `-k` / `--sumo-config` | `sumo.sumocfg` | SUMO config file |
| `-f` / `--fcd-files` | *(required)* | Comma-separated list of FCD XML files to replay |
| `--geo` | off | Interpret FCD coordinates as WGS-84 lon/lat |
| `--length` | `5.0` | Vehicle bounding box length in metres |
| `--width` | `2.0` | Vehicle bounding box width in metres |
| `-v` / `--verbose` | off | Print progress to stdout |

```bash
python sumoData/fcdReplay/fcdReplay.py \
  -k simulation.sumocfg \
  -f replay.fcd.xml

# Multiple FCD files, geo coordinates, custom box size
python sumoData/fcdReplay/fcdReplay.py \
  -k simulation.sumocfg \
  -f run1.fcd.xml,run2.fcd.xml \
  --geo \
  --length 4.5 --width 1.8
```


### Check the size of the database inside the docker container
```bash
docker exec stars_db du -sh /var/lib/postgresql/data
```