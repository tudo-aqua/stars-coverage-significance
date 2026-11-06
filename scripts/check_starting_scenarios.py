"""
Check that every mutant starts each scenario from exactly the same state.

For every (tsc_id, run_id, scenario_config_id, mutant_id) combination in metric_failed_monitors,
the first recorded tick (lowest `tick`) is the scenario's starting state. This script decodes that
tick's `all_vehicles_json_deflate` and compares, per scenario_config_id, the starting state of all
mutants. Compared per vehicle (matched by vehicle id): lane, front and back bumper position, and
speed. The vehicle set itself must match too. Ignored: acceleration (it already reflects the
first decision of the possibly mutated ego) and the vehicle type (the ego is typed "ego" or
"mutant" depending on the mutant).

The first ticks are found with a loose index scan (a recursive CTE that jumps through the unique
index on (tsc_id, run_id, scenario_config_id, mutant_id, tick)), so only one index probe per
combination is needed instead of a scan of the whole table.

Per scenario, the most common starting state is taken as the reference. Every other variant is
reported with its members and the vehicles/fields in which it deviates from the reference.

Usage (in the evaluation container on the server; connects to DB_URI below):
    python3 scripts/check_starting_scenarios.py
    python3 scripts/check_starting_scenarios.py --tsc-id 1 --run-id 3
    python3 scripts/check_starting_scenarios.py --decimals 4

Exit code: 0 if all starting states are identical, 1 if any scenario has deviations.

Writes a JSON report (--json-output, default: starting_scenario_check.json) containing every
deviating scenario with all of its variants and all their members.

Dependencies:
    psycopg2 (already installed as psycopg2-binary in the project's Dockerfile image)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from all_vehicles_json_codec import decompress  # noqa: E402

# The database runs next to the evaluation container on the same server. Direct PostgreSQL port
# (not PgBouncer on 6432): this is one long streaming read inside a single transaction.
DB_URI = "postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars"

# Per-vehicle fields that make up the starting state.
COMPARED_FIELDS = ("lane", "front", "back", "speed")

# Loose index scan: one row (the first tick) per (tsc, run, scenario, mutant) combination.
# The row comparison in the recursive step lets PostgreSQL seek directly to the next combination
# in the unique index instead of reading every tick of the current one.
FIRST_TICKS_QUERY = """
WITH RECURSIVE firsts AS (
    (SELECT m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id, m.tick, m.id
       FROM metric_failed_monitors m
      WHERE {filter}
      ORDER BY m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id, m.tick
      LIMIT 1)
    UNION ALL
    SELECT n.*
      FROM firsts f
      CROSS JOIN LATERAL (
          SELECT m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id, m.tick, m.id
            FROM metric_failed_monitors m
           WHERE (m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id)
                 > (f.tsc_id, f.run_id, f.scenario_config_id, f.mutant_id)
             AND {filter}
           ORDER BY m.tsc_id, m.run_id, m.scenario_config_id, m.mutant_id, m.tick
           LIMIT 1
      ) n
)
SELECT f.scenario_config_id,
       -- Works whether the camelCase column was created quoted or folded to lower case.
       COALESCE(to_jsonb(s) ->> 'humanReadableScenarioId',
                to_jsonb(s) ->> 'humanreadablescenarioid') AS scenario_name,
       f.tsc_id, f.run_id,
       f.mutant_id, mu.mutant_number, mu.class_name, f.tick, mfm.all_vehicles_json_deflate
  FROM firsts f
  JOIN metric_failed_monitors mfm ON mfm.id = f.id
  JOIN mutants mu ON mu.id = f.mutant_id
  JOIN scenario_starting_configurations s ON s.id = f.scenario_config_id
 ORDER BY f.scenario_config_id, mu.mutant_number, f.tsc_id, f.run_id
"""


def starting_state(vehicles_json: str, decimals: int | None) -> tuple:
    """Canonical, hashable starting state: sorted (id, lane, front, back, speed) tuples."""
    state = []
    for v in json.loads(vehicles_json):
        values = tuple(v[f] for f in COMPARED_FIELDS)
        if decimals is not None:
            values = tuple(round(x, decimals) for x in values)
        state.append((v["id"],) + values)
    return tuple(sorted(state))


def diff_states(reference: tuple, other: tuple) -> dict:
    """Describes how `other` deviates from `reference`, per vehicle id and field."""
    ref = {v[0]: v[1:] for v in reference}
    oth = {v[0]: v[1:] for v in other}
    changed = {}
    for vid in sorted(ref.keys() & oth.keys()):
        fields = {
            name: {"reference": r, "this": o}
            for name, r, o in zip(COMPARED_FIELDS, ref[vid], oth[vid])
            if r != o
        }
        if fields:
            changed[vid] = fields
    return {
        "missing_vehicles": sorted(ref.keys() - oth.keys()),
        "extra_vehicles": sorted(oth.keys() - ref.keys()),
        "changed_vehicles": changed,
    }


def describe_member(row: dict) -> dict:
    return {k: row[k] for k in ("mutant_number", "mutant_id", "class_name", "tsc_id", "run_id", "tick")}


def check_scenario(rows: list) -> dict | None:
    """Returns a report for the scenario if its members do not all share one starting state."""
    by_state: dict = {}
    for row in rows:
        by_state.setdefault(row["state"], []).append(row)
    ticks = Counter(row["tick"] for row in rows)

    if len(by_state) == 1 and len(ticks) == 1:
        return None

    variants = sorted(by_state.items(), key=lambda kv: len(kv[1]), reverse=True)
    reference_state = variants[0][0]
    return {
        "scenario_config_id": rows[0]["scenario_config_id"],
        "scenario": rows[0]["scenario"],
        "members": len(rows),
        "first_tick_counts": {str(t): c for t, c in sorted(ticks.items())},
        "variants": [
            {
                "is_reference": i == 0,
                "member_count": len(members),
                "distinct_mutants": len({m["mutant_id"] for m in members}),
                "diff_to_reference": None if i == 0 else diff_states(reference_state, state),
                "state": [dict(zip(("id",) + COMPARED_FIELDS, v)) for v in state],
                "members": [describe_member(m) for m in members],
            }
            for i, (state, members) in enumerate(variants)
        ],
    }


def print_scenario_report(report: dict, max_members: int) -> None:
    print(f"\nScenario {report['scenario_config_id']} ({report['scenario']}): "
          f"{len(report['variants'])} distinct starting states over {report['members']} runs")
    if len(report["first_tick_counts"]) > 1:
        print(f"  first tick differs: {report['first_tick_counts']} (tick: #runs)")
    for i, variant in enumerate(report["variants"]):
        label = "reference" if variant["is_reference"] else f"variant {i}"
        members = variant["members"]
        shown = ", ".join(f"m{m['mutant_number']}(tsc {m['tsc_id']}, run {m['run_id']})"
                          for m in members[:max_members])
        more = f", ... (+{len(members) - max_members})" if len(members) > max_members else ""
        print(f"  [{label}] {variant['member_count']} runs, "
              f"{variant['distinct_mutants']} mutants: {shown}{more}")
        diff = variant["diff_to_reference"]
        if diff is None:
            continue
        if diff["missing_vehicles"]:
            print(f"      missing vehicles: {diff['missing_vehicles']}")
        if diff["extra_vehicles"]:
            print(f"      extra vehicles:   {diff['extra_vehicles']}")
        for vid, fields in diff["changed_vehicles"].items():
            changes = ", ".join(f"{name} {d['reference']} -> {d['this']}" for name, d in fields.items())
            print(f"      {vid}: {changes}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--tsc-id", type=int, default=None,
                        help="Only check this TSC (default: all)")
    parser.add_argument("--run-id", type=int, default=None,
                        help="Only check this evaluation run (default: all)")
    parser.add_argument("--decimals", type=int, default=None,
                        help="Round compared values to this many decimals before comparing "
                             "(default: exact comparison)")
    parser.add_argument("--max-members", type=int, default=10,
                        help="Members listed per variant on stdout (the JSON report lists all; "
                             "default: 10)")
    parser.add_argument("--json-output", default="starting_scenario_check.json", metavar="PATH",
                        help="Path of the JSON report (default: starting_scenario_check.json)")
    args = parser.parse_args()

    import psycopg2

    filters, params = ["TRUE"], []
    if args.tsc_id is not None:
        filters.append("m.tsc_id = %s")
        params.append(args.tsc_id)
    if args.run_id is not None:
        filters.append("m.run_id = %s")
        params.append(args.run_id)
    filter_sql = " AND ".join(filters)
    # The filter appears twice in the query (anchor and recursive step).
    query = FIRST_TICKS_QUERY.format(filter=filter_sql)
    query_params = params + params

    started = time.time()
    print("Collecting the first tick of every (tsc, run, scenario, mutant) combination ...",
          flush=True)

    conn = psycopg2.connect(DB_URI)
    reports, n_rows, n_scenarios = [], 0, 0
    try:
        # Named (server-side) cursor: rows are streamed instead of loaded all at once.
        with conn.cursor(name="starting_scenarios") as cur:
            cur.itersize = 5000
            cur.execute(query, query_params)

            current_id, current_rows = None, []
            for (scenario_id, scenario_name, tsc_id, run_id, mutant_id, mutant_number,
                 class_name, tick, blob) in cur:
                if scenario_id != current_id:
                    if current_rows:
                        report = check_scenario(current_rows)
                        if report:
                            reports.append(report)
                            print_scenario_report(report, args.max_members)
                    current_id, current_rows = scenario_id, []
                    n_scenarios += 1
                current_rows.append({
                    "scenario_config_id": scenario_id,
                    "scenario": scenario_name,
                    "tsc_id": tsc_id,
                    "run_id": run_id,
                    "mutant_id": mutant_id,
                    "mutant_number": mutant_number,
                    "class_name": class_name,
                    "tick": tick,
                    "state": starting_state(decompress(blob), args.decimals),
                })
                n_rows += 1
                if n_rows % 50000 == 0:
                    print(f"  ... {n_rows} starting ticks processed "
                          f"({n_scenarios} scenarios, {time.time() - started:.0f}s)", flush=True)
            if current_rows:
                report = check_scenario(current_rows)
                if report:
                    reports.append(report)
                    print_scenario_report(report, args.max_members)
    finally:
        conn.close()

    deviating_mutants = Counter()
    for report in reports:
        for variant in report["variants"][1:]:
            for m in {(m["mutant_number"], m["class_name"]) for m in variant["members"]}:
                deviating_mutants[m] += 1

    summary = {
        "tsc_id": args.tsc_id,
        "run_id": args.run_id,
        "decimals": args.decimals,
        "compared_fields": list(COMPARED_FIELDS),
        "starting_ticks_checked": n_rows,
        "scenarios_checked": n_scenarios,
        "scenarios_with_deviations": len(reports),
        "deviating_scenarios_per_mutant": [
            {"mutant_number": num, "class_name": cls, "scenarios": count}
            for (num, cls), count in deviating_mutants.most_common()
        ],
    }
    with open(args.json_output, "w") as f:
        json.dump({"summary": summary, "scenarios": reports}, f, indent=2)

    print("\n" + "=" * 70)
    print(f"Starting ticks checked:     {n_rows}")
    print(f"Scenarios checked:          {n_scenarios}")
    print(f"Scenarios with deviations:  {len(reports)}")
    if deviating_mutants:
        print("Mutants outside the reference state (scenarios):")
        for (num, cls), count in deviating_mutants.most_common(20):
            print(f"  m{num} {cls}: {count}")
        if len(deviating_mutants) > 20:
            print(f"  ... (+{len(deviating_mutants) - 20} more, see JSON report)")
    print(f"Report written to {args.json_output} ({time.time() - started:.0f}s)")

    if n_rows == 0:
        sys.exit("No rows found in metric_failed_monitors for the given filters.")
    sys.exit(1 if reports else 0)


if __name__ == "__main__":
    main()
