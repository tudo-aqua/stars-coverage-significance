#!/bin/bash
#python3 scripts/export_parquet.py \
#  --uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars \
#  --output metric_failed_monitors.parquet \
#  --partitions 4
# These are the mutants that have at least 17% of failure rate.
python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
--n-trials 200 \
--no-ego-maneuver \
--no-ego-accel \
--no-distances \
--no-neighbor-kinematics \
--class-weight balanced \
--max-leaves 512 \
--out-dir /results/runs \
--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
--db-workers 48 \
--mutant-numbers 146,132,152,147,18,9,157,154,6,111,115,128 \
&&
#python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
#--no-ego-accel \
#--no-distances \
#--no-neighbor-kinematics \
#--class-weight balanced \
#--max-leaves 512 \
#--out-dir /results/runs \
#--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
#--db-workers 48 \
#&&
#python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
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
#&&
#python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
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
#&&
#python3 -u scripts/decision_tree_g0.py metric_failed_monitors.parquet \
#--n-trials 200 \
#--no-ego-maneuver \
#--no-ego-accel \
#--no-distances \
#--no-neighbor-kinematics \
#--class-weight balanced \
#--max-leaves 512 \
#--out-dir /results/runs \
#--uri postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars \
#--db-workers 48 \
#--mutant-numbers 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,20,23,17,18,19,21,22,24,25,26,27,28,31 \
#&&
./gradlew buildMaterializedViews \
&&
./gradlew runDrawTicksWithDecisionTreeGrouping --args="--all"