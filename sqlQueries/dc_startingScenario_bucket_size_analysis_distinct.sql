SELECT decision_tree_leaf_assignments.leaf_node_id, count(distinct metric_failed_monitors.scenario_config_id) as agg_count
       --,array_agg(distinct metric_failed_monitors.scenario_config_id) as agg
FROM metric_failed_monitors
         JOIN decision_tree_leaf_assignments ON metric_failed_monitors.id = decision_tree_leaf_assignments.metric_failed_monitor_id
WHERE decision_tree_leaf_assignments.run_id = 3
GROUP BY decision_tree_leaf_assignments.leaf_node_id
ORDER BY agg_count