#!/bin/bash
#python3 scripts/export_parquet.py \
#  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars \
#  --output metric_failed_monitors.parquet \
#  --partitions 4 \
#&& python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
#--no-ego-position \
#--no-ego-accel \
#--no-distances \
#--no-neighbor-kinematics \
#--class-weight balanced \
#--max-leaves 512 \
#--out-dir /results/runs \
#--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
#--db-workers 48 \
#&& python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
#--no-ego-position \
#--no-ego-accel \
#--no-distances \
#--no-neighbor-kinematics \
#--class-weight balanced \
#--max-leaves 512 \
#--out-dir /results/runs \
#--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
#--db-workers 48 \
#--seed 42 \
#--train-fraction 0.5 \
#&& python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
#--no-ego-position \
#--no-ego-accel \
#--no-distances \
#--no-neighbor-kinematics \
#--class-weight balanced \
#--max-leaves 512 \
#--out-dir /results/runs \
#--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
#--db-workers 48 \
#--seed 43 \
#--train-fraction 0.5 \
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
--mutant-numbers 26,29,30,32,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58 \
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
--mutant-numbers 1,2,3,4,6,7,8,9,11,12,13,14,15,16,17,21,22,23 \
&& ./gradlew buildMaterializedViews \
&& ./gradlew runDrawTicksWithDecisionTreeGrouping --all