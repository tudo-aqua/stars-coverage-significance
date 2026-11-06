"""
Check that every mutant starts each scenario from exactly the same state.

For every (tsc_id, run_id, scenario_config_id, mutant_id) combination in metric_failed_monitors,
the first recorded tick (lowest `tick`) is the scenario's starting state. This script decodes that
tick's `all_vehicles_json_deflate` and compares, per scenario_config_id, the starting state of all
mutants. Compared per vehicle (matched by vehicle id): lane, front and back bumper position, and
speed. The vehicle set itself must match too. Ignored: acceleration (it already reflects the
first decision of the possibly mutated ego) and the vehicle type (the ego is typed "ego" or
"mutant" depending on the mutant).

How the first ticks are found: the combinations are enumerated from the small dimension tables
(scenario_starting_configurations x mutants x tscs x evaluation_runs) and each first tick is one
direct probe of the unique index on (tsc_id, run_id, scenario_config_id, mutant_id, tick) — no
scan of the ~10^9-row table. The scenarios are split into chunks that worker processes check in
parallel, each over its own database connection, so both the index probes (PostgreSQL side) and
the decompression/comparison (Python side) use many cores. Measured on the server: ~0.36 ms per
probe with a cold cache, i.e. ~3.5 h for 196,608 scenarios x 176 mutants if done serially.

Per scenario, the most common starting state is taken as the reference. Every other variant is
reported with its members and the vehicles/fields in which it deviates from the reference.

Usage (in the evaluation container on the server; connects to DB_URI below):
    python3 scripts/check_starting_scenarios.py
    python3 scripts/check_starting_scenarios.py --max-scenarios 1000   # quick sample first
    python3 scripts/check_starting_scenarios.py --workers 32 --decimals 4

Exit code: 0 if all starting states are identical, 1 if any scenario has deviations.

Writes a JSON report (--json-output, default: starting_scenario_check.json) containing every
deviating scenario with all of its variants and all their members.

Dependencies:
    psycopg2 (already installed as psycopg2-binary in the project's Dockerfile image)
"""

from __future__ import annotations

import argparse
import json
import multiprocessing
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from all_vehicles_json_codec import decompress  # noqa: E402

# The database runs next to the evaluation container on the same server. Direct PostgreSQL port
# (not PgBouncer on 6432), so each worker keeps its own session settings.
DB_URI = "postgresql://stars:stars@ls14-sting1.cs.tu-dortmund.de:5432/stars"

# Per-vehicle fields that make up the starting state.
COMPARED_FIELDS = ("lane", "front", "back", "speed")

# First tick of every (tsc, run, mutant) combination for a chunk of scenarios. The LATERAL
# subquery is a single probe of the unique index (all four leading columns fixed, then ordered by
# tick). Combinations that were never recorded just return no row.
FIRST_TICKS_QUERY = """
SELECT s.id,
       -- Works whether the camelCase column was created quoted or folded to lower case.
       COALESCE(to_jsonb(s) ->> 'humanReadableScenarioId',
                to_jsonb(s) ->> 'humanreadablescenarioid') AS scenario_name,
       t.id, r.id, mu.id, mu.mutant_number, mu.class_name, f.tick, f.all_vehicles_json_deflate
  FROM scenario_starting_configurations s
 CROSS JOIN unnest(%(tsc_ids)s::int[]) AS t(id)
 CROSS JOIN unnest(%(run_ids)s::int[]) AS r(id)
 CROSS JOIN mutants mu
 CROSS JOIN LATERAL (
       SELECT m.tick, m.all_vehicles_json_deflate
         FROM metric_failed_monitors m
        WHERE m.tsc_id = t.id
          AND m.run_id = r.id
          AND m.scenario_config_id = s.id
          AND m.mutant_id = mu.id
        ORDER BY m.tick
        LIMIT 1
 ) f
 WHERE s.id = ANY(%(scenario_ids)s::int[])
 ORDER BY s.id, mu.mutant_number, t.id, r.id
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
            print(f"      {vid}: {changes}", flush=True)


# --- Worker processes -------------------------------------------------------------------------
# Each worker holds one connection for its whole lifetime (set up in _init_worker).

_conn = None
_tsc_ids: list = []
_run_ids: list = []
_decimals: int | None = None


def _connect():
    import psycopg2

    conn = psycopg2.connect(DB_URI)
    # Read-only probes; no need to hold one transaction open across chunks.
    conn.autocommit = True
    with conn.cursor() as cur:
        # The planner wildly overestimates the cross join's row count, which triggers JIT
        # compilation (~250 ms) on every query for nothing.
        cur.execute("SET jit = off")
    return conn


def _init_worker(tsc_ids: list, run_ids: list, decimals: int | None) -> None:
    global _conn, _tsc_ids, _run_ids, _decimals
    _tsc_ids, _run_ids, _decimals = tsc_ids, run_ids, decimals
    _conn = _connect()


def _check_chunk(scenario_ids: list) -> tuple:
    """Checks one chunk of scenarios. Returns (#starting ticks, #scenarios with data, reports)."""
    with _conn.cursor() as cur:
        cur.execute(FIRST_TICKS_QUERY,
                    {"tsc_ids": _tsc_ids, "run_ids": _run_ids, "scenario_ids": scenario_ids})
        rows = cur.fetchall()

    reports, n_scenarios = [], 0
    current_id, current_rows = None, []
    for (scenario_id, scenario_name, tsc_id, run_id, mutant_id, mutant_number,
         class_name, tick, blob) in rows:
        if scenario_id != current_id:
            if current_rows:
                report = check_scenario(current_rows)
                if report:
                    reports.append(report)
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
            "state": starting_state(decompress(blob), _decimals),
        })
    if current_rows:
        report = check_scenario(current_rows)
        if report:
            reports.append(report)
    return len(rows), n_scenarios, reports


# --- Main process -----------------------------------------------------------------------------

def _load_ids(conn, table: str, only: int | None) -> list:
    with conn.cursor() as cur:
        cur.execute(f"SELECT id FROM {table} ORDER BY id")
        ids = [r[0] for r in cur.fetchall()]
    if only is not None:
        if only not in ids:
            sys.exit(f"{table}.id = {only} does not exist.")
        return [only]
    return ids


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
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) // 2),
                        help="Parallel worker processes, each with its own DB connection "
                             "(default: half the CPU cores, since every worker also keeps one "
                             "PostgreSQL backend busy on the same machine)")
    parser.add_argument("--chunk-size", type=int, default=64,
                        help="Scenarios per worker query (default: 64)")
    parser.add_argument("--max-scenarios", type=int, default=None,
                        help="Only check the first N scenarios, e.g. for a quick sample run")
    parser.add_argument("--max-printed", type=int, default=20,
                        help="Deviating scenarios printed in detail on stdout (the JSON report "
                             "lists all; default: 20)")
    parser.add_argument("--max-members", type=int, default=10,
                        help="Members listed per variant on stdout (default: 10)")
    parser.add_argument("--json-output", default="starting_scenario_check.json", metavar="PATH",
                        help="Path of the JSON report (default: starting_scenario_check.json)")
    args = parser.parse_args()

    started = time.time()
    conn = _connect()
    try:
        tsc_ids = _load_ids(conn, "tscs", args.tsc_id)
        run_ids = _load_ids(conn, "evaluation_runs", args.run_id)
        scenario_ids = _load_ids(conn, "scenario_starting_configurations", None)
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM mutants")
            n_mutants = cur.fetchone()[0]
    finally:
        conn.close()

    if args.max_scenarios is not None:
        scenario_ids = scenario_ids[:args.max_scenarios]
    chunks = [scenario_ids[i:i + args.chunk_size]
              for i in range(0, len(scenario_ids), args.chunk_size)]
    expected = len(scenario_ids) * n_mutants * len(tsc_ids) * len(run_ids)
    print(f"{len(scenario_ids)} scenarios x {n_mutants} mutants x {len(tsc_ids)} TSCs x "
          f"{len(run_ids)} runs = up to {expected} starting ticks, "
          f"{len(chunks)} chunks on {args.workers} workers", flush=True)

    reports, n_rows, n_scenarios, n_printed = [], 0, 0, 0
    progress_every = max(1, len(chunks) // 200)
    with multiprocessing.Pool(args.workers, initializer=_init_worker,
                              initargs=(tsc_ids, run_ids, args.decimals)) as pool:
        for done, (chunk_rows, chunk_scenarios, chunk_reports) in enumerate(
                pool.imap_unordered(_check_chunk, chunks), start=1):
            n_rows += chunk_rows
            n_scenarios += chunk_scenarios
            reports.extend(chunk_reports)
            for report in chunk_reports:
                if n_printed < args.max_printed:
                    print_scenario_report(report, args.max_members)
                    n_printed += 1
            if done % progress_every == 0 or done == len(chunks):
                elapsed = time.time() - started
                eta = elapsed / done * (len(chunks) - done)
                print(f"  [{done}/{len(chunks)} chunks] {n_rows} starting ticks, "
                      f"{n_scenarios} scenarios, {len(reports)} deviating | "
                      f"{elapsed / 60:.1f} min elapsed, ~{eta / 60:.1f} min left", flush=True)

    reports.sort(key=lambda r: r["scenario_config_id"])
    deviating_mutants = Counter()
    for report in reports:
        for variant in report["variants"][1:]:
            for m in {(m["mutant_number"], m["class_name"]) for m in variant["members"]}:
                deviating_mutants[m] += 1

    summary = {
        "tsc_ids": tsc_ids,
        "run_ids": run_ids,
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
    print(f"Starting ticks checked:     {n_rows} (of up to {expected} combinations)")
    print(f"Scenarios checked:          {n_scenarios}")
    print(f"Scenarios with deviations:  {len(reports)}")
    if deviating_mutants:
        print("Mutants outside the reference state (scenarios):")
        for (num, cls), count in deviating_mutants.most_common(20):
            print(f"  m{num} {cls}: {count}")
        if len(deviating_mutants) > 20:
            print(f"  ... (+{len(deviating_mutants) - 20} more, see JSON report)")
    print(f"Report written to {args.json_output} ({(time.time() - started) / 60:.1f} min)")

    if n_rows == 0:
        sys.exit("No rows found in metric_failed_monitors for the given filters.")
    sys.exit(1 if reports else 0)


if __name__ == "__main__":
    main()
