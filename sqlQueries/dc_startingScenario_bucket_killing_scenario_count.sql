SELECT leaf_node_id,
       array_agg(DISTINCT scenario_config_id)                                          AS scenarios,
       count(DISTINCT scenario_config_id)                                              AS scenario_count,
       count(DISTINCT CASE WHEN any_g0_violation THEN scenario_config_id END)          AS killing_scenario_count
FROM dc_startingscenario_mutant_combination
GROUP BY leaf_node_id
ORDER BY scenario_count
