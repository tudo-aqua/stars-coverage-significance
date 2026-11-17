-- Step A.1 of docs/test-bed-plan.md: one row per (scenario_config_id, mutant_id) with the run's
-- accident flag and the minimum own-lane TTC / TG, built in one pass over metric_failed_monitors.
--
-- Run twice, baseline first (about 8M ticks), then the mutants (about 1.4B ticks):
--   psql -v scope=baseline -f sqlQueries/scenario_mutant_outcomes.sql
--   psql -v scope=mutants  -f sqlQueries/scenario_mutant_outcomes.sql
-- Each run replaces only the rows of its scope, so either one can be rerun on its own.
-- Optional: -v lc_window=20 (ticks after an ego lane change that count for the *_after_lc columns).
--
-- NULL in a min_*_ttc / min_*_tg column means no finite value during the run (+infinity).

\set ON_ERROR_STOP on
\timing on

\if :{?scope}
\else
  \set scope baseline
\endif
\if :{?lc_window}
\else
  \set lc_window 20
\endif

SELECT :'scope' IN ('baseline', 'mutants') AS scope_ok \gset
\if :scope_ok
\else
  \echo 'scope must be baseline or mutants, got' :'scope'
  \quit
\endif

CREATE TEMP VIEW outcomes_src AS
WITH ticks AS (
    SELECT m.tsc_id,
           m.run_id,
           m.scenario_config_id,
           m.mutant_id,
           m.tick,
           m."monitor_g0_Accidents_failed" AS g0,
           m.surrounding_front_ttc_s       AS front_ttc,
           m.surrounding_front_tg_s        AS front_tg,
           m.surrounding_rear_ttc_s        AS rear_ttc,
           m.surrounding_rear_tg_s         AS rear_tg,
           m.ego_lane <> LAG(m.ego_lane) OVER w AS lane_changed
    FROM metric_failed_monitors m
         JOIN mutants mu ON mu.id = m.mutant_id
    WHERE (mu.mutant_number = -1) = (:'scope' = 'baseline')
    -- Same column order as the unique index, so Postgres can read it pre-sorted.
    WINDOW w AS (PARTITION BY m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id ORDER BY m.tick)
),
marked AS (
    SELECT t.*,
           t.tick - MAX(CASE WHEN t.lane_changed THEN t.tick END) OVER (
               PARTITION BY t.tsc_id, t.run_id, t.scenario_config_id, t.mutant_id ORDER BY t.tick
           ) <= :lc_window AS after_lc
    FROM ticks t
)
SELECT scenario_config_id,
       mutant_id,
       BOOL_OR(g0)                                AS accident_any,
       MIN(tick) FILTER (WHERE g0)                AS first_accident_tick,
       MIN(front_ttc)                             AS min_front_ttc,
       MIN(front_tg)                              AS min_front_tg,
       MIN(rear_ttc)                              AS min_rear_ttc,
       MIN(rear_tg)                               AS min_rear_tg,
       MIN(rear_ttc) FILTER (WHERE after_lc)      AS min_rear_ttc_after_lc,
       MIN(rear_tg) FILTER (WHERE after_lc)       AS min_rear_tg_after_lc,
       COUNT(*) FILTER (WHERE lane_changed)       AS n_lane_changes,
       COUNT(*)                                   AS n_ticks,
       COUNT(DISTINCT (tsc_id, run_id))           AS n_runs,
       :lc_window                                 AS lc_window_ticks
FROM marked
GROUP BY scenario_config_id, mutant_id;

CREATE TABLE IF NOT EXISTS scenario_mutant_outcomes AS SELECT * FROM outcomes_src WITH NO DATA;
CREATE UNIQUE INDEX IF NOT EXISTS scenario_mutant_outcomes_pk
    ON scenario_mutant_outcomes (scenario_config_id, mutant_id);

BEGIN;
DELETE FROM scenario_mutant_outcomes o
USING mutants mu
WHERE mu.id = o.mutant_id
  AND (mu.mutant_number = -1) = (:'scope' = 'baseline');
INSERT INTO scenario_mutant_outcomes SELECT * FROM outcomes_src;
COMMIT;

ANALYZE scenario_mutant_outcomes;

-- Acceptance checks (plan step A): n_runs > 1 means the pair has ticks from several tsc/run ids,
-- and mixed lc_window values mean the two scopes were built with different windows.
SELECT (mu.mutant_number = -1)              AS baseline,
       COUNT(*)                             AS pairs,
       COUNT(DISTINCT o.scenario_config_id) AS scenarios,
       COUNT(DISTINCT o.mutant_id)          AS controllers,
       COUNT(*) FILTER (WHERE o.n_runs > 1) AS pairs_with_several_runs,
       COUNT(*) FILTER (WHERE o.accident_any) AS pairs_with_accident,
       MIN(o.lc_window_ticks)               AS lc_window_min,
       MAX(o.lc_window_ticks)               AS lc_window_max
FROM scenario_mutant_outcomes o
     JOIN mutants mu ON mu.id = o.mutant_id
GROUP BY 1
ORDER BY 1 DESC;
