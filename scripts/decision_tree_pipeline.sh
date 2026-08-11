#!/bin/bash
psql "postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:6432/stars" -c "
  BEGIN;
  DELETE FROM decision_tree_runs WHERE id IN (5, 6);
  SELECT setval(pg_get_serial_sequence('decision_tree_runs', 'id'), 5, false);
  COMMIT;" \
&&
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
&& ./gradlew buildMaterializedViews \
&& ./gradlew runDrawTicksWithDecisionTreeGrouping --args="5,6"