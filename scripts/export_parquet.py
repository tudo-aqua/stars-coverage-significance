"""
Export metric_failed_monitors from PostgreSQL to Parquet.

Uses connectorx to read the table in parallel partitions across all available
cores, then writes a snappy-compressed Parquet file that polars and the
decision-tree script can load in seconds.

Usage:
    python export_parquet.py --uri postgresql://user:pass@host:5432/db
    python export_parquet.py --uri postgresql://user:pass@host:5432/db --output out.parquet --partitions 96

Dependencies:
    pip install polars connectorx
"""

import argparse
import sys
from pathlib import Path

import polars as pl


# UUID and timestamp columns are excluded — they carry no signal for the classifier.
# All seven next-tick monitor columns are kept so the same Parquet file can be
# reused for other target columns without re-exporting.
QUERY = """
SELECT
    tick,
    ego_maneuver_speed,
    ego_maneuver_lane_change,
    "monitor_g0_Accidents_failed",
    "monitor_g1_SafeDistanceToPrecedingVehicle_failed",
    "monitor_g2_emergencyBraking_failed",
    "monitor_g3_MaximumSpeedLimit_failed",
    "monitor_g4_TrafficFlow_failed",
    "monitor_i1_Stopping_failed",
    "monitor_i2_DrivingFasterThenLeftTraffic_failed",
    "next_tick_monitor_g0_Accidents_failed",
    "next_tick_monitor_g1_SafeDistanceToPrecedingVehicle_failed",
    "next_tick_monitor_g2_emergencyBraking_failed",
    "next_tick_monitor_g3_MaximumSpeedLimit_failed",
    "next_tick_monitor_g4_TrafficFlow_failed",
    "next_tick_monitor_i1_Stopping_failed",
    "next_tick_monitor_i2_DrivingFasterThenLeftTraffic_failed",
    surrounding_dist_front,
    surrounding_dist_rear,
    surrounding_dist_front_left,
    surrounding_dist_front_right,
    surrounding_dist_rear_left,
    surrounding_dist_rear_right,
    surrounding_dist_left,
    surrounding_dist_right
FROM metric_failed_monitors
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--uri",
        required=True,
        help="PostgreSQL connection URI: postgresql://user:pass@host:port/db",
    )
    parser.add_argument(
        "--output",
        default="metric_failed_monitors.parquet",
        help="Output Parquet file path (default: metric_failed_monitors.parquet)",
    )
    parser.add_argument(
        "--partitions",
        type=int,
        default=96,
        help="Number of parallel read partitions matching available cores (default: 96)",
    )
    args = parser.parse_args()

    print(f"Reading metric_failed_monitors with {args.partitions} parallel partitions …")
    try:
        # connectorx issues N parallel SELECT queries with non-overlapping WHERE
        # clauses on `tick`, then assembles the result as a zero-copy Arrow table.
        df = pl.read_database_uri(
            query=QUERY,
            uri=args.uri,
            partition_on="tick",
            partition_num=args.partitions,
            engine="connectorx",
        )
    except Exception as exc:
        sys.exit(f"Database read failed: {exc}")

    output = Path(args.output)
    df.write_parquet(output, compression="snappy")

    size_mb = output.stat().st_size / 1_048_576
    print(f"Exported {len(df):,} rows → {output}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
