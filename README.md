# STARS Coverage Significance — Docker Usage

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

---

## Python Scripts

All Python dependencies are installed in the Docker image. To run the scripts outside Docker, install them first:

```bash
pip install matplotlib numpy pandas scipy lightgbm polars connectorx graphviz psycopg2-binary scikit-learn optuna
```

---

### `scripts/export_parquet.py` — Export PostgreSQL table to Parquet

Exports the `metric_failed_monitors` table from PostgreSQL to a Parquet file using parallel reads via [connectorx](https://github.com/sfu-db/connector-x). On a server with many cores this is significantly faster than a single-threaded CSV export.

| Argument | Default | Description |
|---|---|---|
| `--uri` | *(required)* | PostgreSQL connection URI: `postgresql://user:pass@host:port/db` |
| `--output` | `metric_failed_monitors.parquet` | Output Parquet file path |
| `--partitions` | `96` | Number of parallel read partitions; set to match available CPU cores |

```bash
python3 scripts/export_parquet.py \
  --uri postgresql://stars:stars@host:5432/db \
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
|---|---|---|
| `parquet` | *(required)* | Path to the Parquet export of `metric_failed_monitors` |
| `--n-trials` | `50` | Number of Optuna trials for automatic hyperparameter tuning |
| `--n-jobs` | `96` | CPU threads used by LightGBM; set to match available cores |
| `--train-fraction` | `1.0` | Fraction of unique mutant IDs used for training (`0 < F ≤ 1.0`); the remaining mutants form the test set |
| `--seed` | `42` | Random seed for the mutant train/test shuffle |
| `--output` | *(none)* | Write a Graphviz `.dot` file to an explicit path (overrides the run-named file from `--out-dir`) |
| `--annotate` | *(none)* | Write a Parquet file containing features + target + `leaf_node_id` for every row |
| `--uri` | *(none)* | Record the run and write leaf assignments to the database: `postgresql://user:pass@host:port/db` |
| `--db-workers` | `48` | Parallel database connections used when writing leaf assignments via `--uri` |
| `--out-dir` | parquet's directory | Directory for run-named output files (`run_<id>.dot`, `run_<id>.log`); only used when `--uri` is set |

When `--uri` is provided the script automatically captures the full stdout log and the DOT source, stores both in the `decision_tree_runs` table, and writes `run_<id>.dot` / `run_<id>.log` to `--out-dir`.

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
  --out-dir /results/runs

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
