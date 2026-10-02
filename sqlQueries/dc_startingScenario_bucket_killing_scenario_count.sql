SELECT leaf_node_id,
       array_agg(scenario_config_id)                                     AS scenarios,
       count(*)                                                          AS scenario_count,
       count(*) FILTER (WHERE any_g0_violation)                          AS killing_scenario_count
FROM dc_leaf_scenarios
WHERE decision_tree_run_id = 3
GROUP BY leaf_node_id
ORDER BY scenario_count
