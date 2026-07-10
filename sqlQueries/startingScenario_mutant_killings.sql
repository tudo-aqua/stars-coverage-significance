SELECT scenario_config_id, COUNT(DISTINCT mutant_id) AS killed_mutant_count
FROM mutant_scenario_g0_violations
WHERE any_g0_violation = TRUE
GROUP BY scenario_config_id
