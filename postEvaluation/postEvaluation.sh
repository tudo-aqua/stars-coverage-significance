#!/bin/bash

../.venv/bin/python3 count_of_mutants_killed_per_monitor/countOfMutantsKilledPerMonitor.py &
../.venv/bin/python3 count_of_scenarios_killing_mutant_per_mutant/countOfMutantsKilledPerMonitor.py &
../.venv/bin/python3 highway_traffic_analysis/highwayTrafficAnalysis.py &
../.venv/bin/python3 mutant_killing/countOfKilledMutants_scatter.py &
../.venv/bin/python3 mutant_killing/countOfMutantsKilledWithMonitors_scatter.py &
../.venv/bin/python3 mutants_killed_by_monitors_per_scenario/mutantsKilledByMonitorPerScenario.py &
../.venv/bin/python3 scenario_by_monitor_cross_table/scenarioByMonitorCrossTable.py &
../.venv/bin/python3 scenario_by_scenario_cross_table/scenarioByScenarioCrossTable.py &
wait
