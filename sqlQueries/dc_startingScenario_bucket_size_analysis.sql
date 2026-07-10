SELECT decision_tree_leaf_assignments.leaf_node_id, count(*) as count, array_agg(metric_failed_monitors.scenario_config_id)
FROM metric_failed_monitors
         JOIN decision_tree_leaf_assignments ON metric_failed_monitors.id = decision_tree_leaf_assignments.metric_failed_monitor_id
WHERE decision_tree_leaf_assignments.run_id = 3
GROUP BY decision_tree_leaf_assignments.leaf_node_id
ORDER BY count