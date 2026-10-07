/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance.db.tables

import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.db.hexBytea
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DuplicateTickColumns
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.NextTickPostEvaluationDatabaseEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceTransition
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toMonitorViolations
import tools.aqua.stars.coverage.significance.utils.compressAllVehiclesJson
import tools.aqua.stars.coverage.significance.utils.decompressAllVehiclesJson
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.sumo.HighwayLane
import tools.aqua.stars.sumo.LaneChangeDirection

/**
 * Table for storing the failed monitors for a mutant in a scenario starting-configuration and
 * evaluation run.
 *
 * @property tsc TSC.
 * @property run Evaluation run.
 * @property startingScenarioConfiguration Scenario starting configuration.
 * @property mutant Mutant.
 * @property currentTSCInstance Current TSC instance.
 * @property lastTickTSCInstance Last TSC instance tick.
 * @property tick TSC instance tick.
 * @property egoManeuverSpeed Ego planned maneuver speed (m/s).
 * @property egoManeuverLaneChange Ego planned lane-change direction.
 * @property egoLane Lane the ego vehicle is currently on.
 * @property egoSpeedMps Ego vehicle speed (m/s).
 * @property egoAccelMps2 Ego vehicle acceleration (m/s²).
 * @property monitorG0Failed Whether monitor G0 failed.
 * @property monitorG1Failed Whether monitor G1 failed.
 * @property monitorG2Failed Whether monitor G2 failed.s
 * @property monitorG3Failed Whether monitor G3 failed.
 * @property monitorG4Failed Whether monitor G4 failed.
 * @property monitorI1Failed Whether monitor I1 failed.
 * @property monitorI2Failed Whether monitor I2 failed.
 * @property nextTickMonitorG0Failed Whether monitor G0 failed in the next tick (null = last tick).
 * @property nextTickMonitorG1Failed Whether monitor G1 failed in the next tick (null = last tick).
 * @property nextTickMonitorG2Failed Whether monitor G2 failed in the next tick (null = last tick).
 * @property nextTickMonitorG3Failed Whether monitor G3 failed in the next tick (null = last tick).
 * @property nextTickMonitorG4Failed Whether monitor G4 failed in the next tick (null = last tick).
 * @property nextTickMonitorI1Failed Whether monitor I1 failed in the next tick (null = last tick).
 * @property nextTickMonitorI2Failed Whether monitor I2 failed in the next tick (null = last tick).
 * @property surroundingDistFront Bumper-to-bumper distance to the nearest vehicle fully ahead on
 *   the same lane (m).
 * @property surroundingFrontSpeedMps Speed of the front neighbour (m/s).
 * @property surroundingFrontAccelMps2 Acceleration of the front neighbour (m/s²).
 * @property surroundingFrontSpeedDiffMps Speed difference to the front neighbour (neighbourSpeed −
 *   egoSpeed, m/s).
 * @property surroundingFrontAccelDiffMps2 Acceleration difference to the front neighbour
 *   (neighbourAccel − egoAccel, m/s²).
 * @property surroundingFrontTtcSeconds Time-to-collision to the front neighbour (s); null when not
 *   closing.
 * @property surroundingFrontTgSeconds Time gap to the front neighbour from ego's perspective (s).
 * @property surroundingDistRear Bumper-to-bumper distance to the nearest vehicle fully behind on
 *   the same lane (m).
 * @property surroundingRearSpeedMps Speed of the rear neighbour (m/s).
 * @property surroundingRearAccelMps2 Acceleration of the rear neighbour (m/s²).
 * @property surroundingRearSpeedDiffMps Speed difference to the rear neighbour (m/s).
 * @property surroundingRearAccelDiffMps2 Acceleration difference to the rear neighbour (m/s²).
 * @property surroundingRearTtcSeconds Time-to-collision to the rear neighbour (s); null when not
 *   closing.
 * @property surroundingRearTgSeconds Time gap to the rear neighbour from follower's perspective
 *   (s).
 * @property surroundingDistFrontLeft Bumper-to-bumper distance to the nearest vehicle whose rear
 *   bumper is at or ahead of the ego's front bumper on the left lane (m; 0 when touching).
 * @property surroundingFrontLeftSpeedMps Speed of the front-left neighbour (m/s).
 * @property surroundingFrontLeftAccelMps2 Acceleration of the front-left neighbour (m/s²).
 * @property surroundingFrontLeftSpeedDiffMps Speed difference to the front-left neighbour (m/s).
 * @property surroundingFrontLeftAccelDiffMps2 Acceleration difference to the front-left neighbour
 *   (m/s²).
 * @property surroundingFrontLeftTtcSeconds Time-to-collision to the front-left neighbour (s; 0 when
 *   touching, null when not closing).
 * @property surroundingFrontLeftTgSeconds Time gap to the front-left neighbour (s; 0 when
 *   touching).
 * @property surroundingDistFrontRight Bumper-to-bumper distance to the nearest vehicle whose rear
 *   bumper is at or ahead of the ego's front bumper on the right lane (m; 0 when touching).
 * @property surroundingFrontRightSpeedMps Speed of the front-right neighbour (m/s).
 * @property surroundingFrontRightAccelMps2 Acceleration of the front-right neighbour (m/s²).
 * @property surroundingFrontRightSpeedDiffMps Speed difference to the front-right neighbour (m/s).
 * @property surroundingFrontRightAccelDiffMps2 Acceleration difference to the front-right neighbour
 *   (m/s²).
 * @property surroundingFrontRightTtcSeconds Time-to-collision to the front-right neighbour (s; 0
 *   when touching, null when not closing).
 * @property surroundingFrontRightTgSeconds Time gap to the front-right neighbour (s; 0 when
 *   touching).
 * @property surroundingDistRearLeft Bumper-to-bumper distance to the nearest vehicle whose front
 *   bumper is at or behind the ego's rear bumper on the left lane (m; 0 when touching).
 * @property surroundingRearLeftSpeedMps Speed of the rear-left neighbour (m/s).
 * @property surroundingRearLeftAccelMps2 Acceleration of the rear-left neighbour (m/s²).
 * @property surroundingRearLeftSpeedDiffMps Speed difference to the rear-left neighbour (m/s).
 * @property surroundingRearLeftAccelDiffMps2 Acceleration difference to the rear-left neighbour
 *   (m/s²).
 * @property surroundingRearLeftTtcSeconds Time-to-collision to the rear-left neighbour (s; 0 when
 *   touching, null when not closing).
 * @property surroundingRearLeftTgSeconds Time gap to the rear-left neighbour (s; 0 when touching).
 * @property surroundingDistRearRight Bumper-to-bumper distance to the nearest vehicle whose front
 *   bumper is at or behind the ego's rear bumper on the right lane (m; 0 when touching).
 * @property surroundingRearRightSpeedMps Speed of the rear-right neighbour (m/s).
 * @property surroundingRearRightAccelMps2 Acceleration of the rear-right neighbour (m/s²).
 * @property surroundingRearRightSpeedDiffMps Speed difference to the rear-right neighbour (m/s).
 * @property surroundingRearRightAccelDiffMps2 Acceleration difference to the rear-right neighbour
 *   (m/s²).
 * @property surroundingRearRightTtcSeconds Time-to-collision to the rear-right neighbour (s; 0 when
 *   touching, null when not closing).
 * @property surroundingRearRightTgSeconds Time gap to the rear-right neighbour (s; 0 when
 *   touching).
 * @property collisionTimeSeconds Time at which the ego-relevant collision occurred (s).
 * @property collisionType SUMO collision type string.
 * @property collisionLane Lane on which the collision occurred.
 * @property collisionPositionOnLaneMeters Position on lane where the collision occurred (m).
 * @property collisionColliderVehicleId Vehicle ID of the collider.
 * @property collisionColliderLane Lane of the collider vehicle at collision time.
 * @property collisionColliderSpeedMps Speed of the collider vehicle at collision time (m/s).
 * @property collisionColliderAccelMps2 Acceleration of the collider vehicle at collision time
 *   (m/s²).
 * @property collisionColliderFrontBumperPosMeters Front bumper position of the collider vehicle at
 *   collision time (m).
 * @property collisionColliderBackBumperPosMeters Back bumper position of the collider vehicle at
 *   collision time (m).
 * @property collisionVictimVehicleId Vehicle ID of the victim.
 * @property collisionVictimLane Lane of the victim vehicle at collision time.
 * @property collisionVictimSpeedMps Speed of the victim vehicle at collision time (m/s).
 * @property collisionVictimAccelMps2 Acceleration of the victim vehicle at collision time (m/s²).
 * @property collisionVictimFrontBumperPosMeters Front bumper position of the victim vehicle at
 *   collision time (m).
 * @property collisionVictimBackBumperPosMeters Back bumper position of the victim vehicle at
 *   collision time (m).
 * @property allVehiclesJsonDeflate Raw-DEFLATE-compressed JSON array of every vehicle present at
 *   this tick (see [compressAllVehiclesJson] and
 *   [tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot]).
 * @property createdAt Timestamp of creation.
 */
object MetricFailedMonitorsTable : LongIdTable("metric_failed_monitors") {
  val tsc =
      reference(
          name = "tsc_id",
          foreign = TSCsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val run =
      reference(
          name = "run_id",
          foreign = EvaluationRunsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val startingScenarioConfiguration =
      reference(
          name = "scenario_config_id",
          foreign = ScenarioStartingConfigurationTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val mutant =
      reference(
          name = "mutant_id",
          foreign = MutantsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val currentTSCInstance =
      reference(
          name = "current_tsc_instance_id",
          foreign = TSCInstancesTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val lastTickTSCInstance =
      reference(
              name = "last_tsc_instance_id",
              foreign = TSCInstancesTable,
              onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
              onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
          .nullable()
  val tick = long("tick")
  val egoManeuverSpeed = float("ego_maneuver_speed").nullable()
  val egoManeuverLaneChange =
      enumerationByName("ego_maneuver_lane_change", 20, LaneChangeDirection::class).nullable()
  val egoLane = enumerationByName("ego_lane", 6, HighwayLane::class).nullable()
  val egoSpeedMps = float("ego_speed_mps").nullable()
  val egoAccelMps2 = float("ego_accel_mps2").nullable()
  val monitorG0Failed = bool("monitor_g0_Accidents_failed").default(false)
  val monitorG1Failed = bool("monitor_g1_SafeDistanceToPrecedingVehicle_failed").default(false)
  val monitorG2Failed = bool("monitor_g2_emergencyBraking_failed").default(false)
  val monitorG3Failed = bool("monitor_g3_MaximumSpeedLimit_failed").default(false)
  val monitorG4Failed = bool("monitor_g4_TrafficFlow_failed").default(false)
  val monitorI1Failed = bool("monitor_i1_Stopping_failed").default(false)
  val monitorI2Failed = bool("monitor_i2_DrivingFasterThenLeftTraffic_failed").default(false)
  val nextTickMonitorG0Failed = bool("next_tick_monitor_g0_Accidents_failed").nullable()
  val nextTickMonitorG1Failed =
      bool("next_tick_monitor_g1_SafeDistanceToPrecedingVehicle_failed").nullable()
  val nextTickMonitorG2Failed = bool("next_tick_monitor_g2_emergencyBraking_failed").nullable()
  val nextTickMonitorG3Failed = bool("next_tick_monitor_g3_MaximumSpeedLimit_failed").nullable()
  val nextTickMonitorG4Failed = bool("next_tick_monitor_g4_TrafficFlow_failed").nullable()
  val nextTickMonitorI1Failed = bool("next_tick_monitor_i1_Stopping_failed").nullable()
  val nextTickMonitorI2Failed =
      bool("next_tick_monitor_i2_DrivingFasterThenLeftTraffic_failed").nullable()
  val surroundingDistFront = float("surrounding_dist_front").nullable()
  val surroundingFrontSpeedMps = float("surrounding_front_speed_mps").nullable()
  val surroundingFrontAccelMps2 = float("surrounding_front_accel_mps2").nullable()
  val surroundingFrontSpeedDiffMps = float("surrounding_front_speed_diff_mps").nullable()
  val surroundingFrontAccelDiffMps2 = float("surrounding_front_accel_diff_mps2").nullable()
  val surroundingFrontTtcSeconds = float("surrounding_front_ttc_s").nullable()
  val surroundingFrontTgSeconds = float("surrounding_front_tg_s").nullable()
  val surroundingDistRear = float("surrounding_dist_rear").nullable()
  val surroundingRearSpeedMps = float("surrounding_rear_speed_mps").nullable()
  val surroundingRearAccelMps2 = float("surrounding_rear_accel_mps2").nullable()
  val surroundingRearSpeedDiffMps = float("surrounding_rear_speed_diff_mps").nullable()
  val surroundingRearAccelDiffMps2 = float("surrounding_rear_accel_diff_mps2").nullable()
  val surroundingRearTtcSeconds = float("surrounding_rear_ttc_s").nullable()
  val surroundingRearTgSeconds = float("surrounding_rear_tg_s").nullable()
  val surroundingDistFrontLeft = float("surrounding_dist_front_left").nullable()
  val surroundingFrontLeftSpeedMps = float("surrounding_front_left_speed_mps").nullable()
  val surroundingFrontLeftAccelMps2 = float("surrounding_front_left_accel_mps2").nullable()
  val surroundingFrontLeftSpeedDiffMps = float("surrounding_front_left_speed_diff_mps").nullable()
  val surroundingFrontLeftAccelDiffMps2 = float("surrounding_front_left_accel_diff_mps2").nullable()
  val surroundingFrontLeftTtcSeconds = float("surrounding_front_left_ttc_s").nullable()
  val surroundingFrontLeftTgSeconds = float("surrounding_front_left_tg_s").nullable()
  val surroundingDistFrontRight = float("surrounding_dist_front_right").nullable()
  val surroundingFrontRightSpeedMps = float("surrounding_front_right_speed_mps").nullable()
  val surroundingFrontRightAccelMps2 = float("surrounding_front_right_accel_mps2").nullable()
  val surroundingFrontRightSpeedDiffMps = float("surrounding_front_right_speed_diff_mps").nullable()
  val surroundingFrontRightAccelDiffMps2 =
      float("surrounding_front_right_accel_diff_mps2").nullable()
  val surroundingFrontRightTtcSeconds = float("surrounding_front_right_ttc_s").nullable()
  val surroundingFrontRightTgSeconds = float("surrounding_front_right_tg_s").nullable()
  val surroundingDistRearLeft = float("surrounding_dist_rear_left").nullable()
  val surroundingRearLeftSpeedMps = float("surrounding_rear_left_speed_mps").nullable()
  val surroundingRearLeftAccelMps2 = float("surrounding_rear_left_accel_mps2").nullable()
  val surroundingRearLeftSpeedDiffMps = float("surrounding_rear_left_speed_diff_mps").nullable()
  val surroundingRearLeftAccelDiffMps2 = float("surrounding_rear_left_accel_diff_mps2").nullable()
  val surroundingRearLeftTtcSeconds = float("surrounding_rear_left_ttc_s").nullable()
  val surroundingRearLeftTgSeconds = float("surrounding_rear_left_tg_s").nullable()
  val surroundingDistRearRight = float("surrounding_dist_rear_right").nullable()
  val surroundingRearRightSpeedMps = float("surrounding_rear_right_speed_mps").nullable()
  val surroundingRearRightAccelMps2 = float("surrounding_rear_right_accel_mps2").nullable()
  val surroundingRearRightSpeedDiffMps = float("surrounding_rear_right_speed_diff_mps").nullable()
  val surroundingRearRightAccelDiffMps2 = float("surrounding_rear_right_accel_diff_mps2").nullable()
  val surroundingRearRightTtcSeconds = float("surrounding_rear_right_ttc_s").nullable()
  val surroundingRearRightTgSeconds = float("surrounding_rear_right_tg_s").nullable()
  val collisionTimeSeconds = float("collision_time_seconds").nullable()
  val collisionType = text("collision_type").nullable()
  val collisionLane = enumerationByName("collision_lane", 6, HighwayLane::class).nullable()
  val collisionPositionOnLaneMeters = float("collision_position_on_lane_meters").nullable()
  val collisionColliderVehicleId = text("collision_collider_vehicle_id").nullable()
  val collisionColliderLane =
      enumerationByName("collision_collider_lane", 6, HighwayLane::class).nullable()
  val collisionColliderSpeedMps = float("collision_collider_speed_mps").nullable()
  val collisionColliderAccelMps2 = float("collision_collider_accel_mps2").nullable()
  val collisionColliderFrontBumperPosMeters =
      float("collision_collider_front_bumper_pos_meters").nullable()
  val collisionColliderBackBumperPosMeters =
      float("collision_collider_back_bumper_pos_meters").nullable()
  val collisionVictimVehicleId = text("collision_victim_vehicle_id").nullable()
  val collisionVictimLane =
      enumerationByName("collision_victim_lane", 6, HighwayLane::class).nullable()
  val collisionVictimSpeedMps = float("collision_victim_speed_mps").nullable()
  val collisionVictimAccelMps2 = float("collision_victim_accel_mps2").nullable()
  val collisionVictimFrontBumperPosMeters =
      float("collision_victim_front_bumper_pos_meters").nullable()
  val collisionVictimBackBumperPosMeters =
      float("collision_victim_back_bumper_pos_meters").nullable()

  /**
   * JSON array of
   * [tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot] — every
   * vehicle present at this tick, not just the nearest one in each `surrounding*` cell — compressed
   * with [compressAllVehiclesJson] (read back with [decompressAllVehiclesJson]). Stored compressed
   * because it is ~75% of an uncompressed row, and PostgreSQL does not compress rows this small on
   * its own.
   */
  val allVehiclesJsonDeflate = hexBytea("all_vehicles_json_deflate")

  val createdAt = timestamp("created_at")

  init {
    // Also serves lookups by tsc alone, since tsc is its leading column.
    index(true, tsc, run, startingScenarioConfiguration, mutant, tick)

    index(false, run)
    index(false, startingScenarioConfiguration)
    index(false, mutant)
    index(false, tick)

    // Partial indexes: only the rare `true` rows are ever looked up, so indexing all ~10⁹ rows
    // would waste gigabytes.
    index("mfm_monitor_g0_failed_true", false, monitorG0Failed) { monitorG0Failed eq true }
    index("mfm_next_tick_monitor_g0_failed_true", false, nextTickMonitorG0Failed) {
      nextTickMonitorG0Failed eq true
    }
  }

  /**
   * Columns compared by [buildDuplicateTickCompareColumns] when checking for duplicate ticks:
   * relative bumper-to-bumper distances to each neighbour, ego/neighbour speeds, and ego/neighbour
   * accelerations. Absolute lane positions and monitor/target columns are deliberately excluded —
   * only the relative/relational kinematic state is compared.
   */
  private val duplicateTickCompareColumns: List<Column<Float?>> =
      listOf(
          surroundingDistFront,
          surroundingDistRear,
          surroundingDistFrontLeft,
          surroundingDistFrontRight,
          surroundingDistRearLeft,
          surroundingDistRearRight,
          egoSpeedMps,
          surroundingFrontSpeedMps,
          surroundingRearSpeedMps,
          surroundingFrontLeftSpeedMps,
          surroundingFrontRightSpeedMps,
          surroundingRearLeftSpeedMps,
          surroundingRearRightSpeedMps,
          egoAccelMps2,
          surroundingFrontAccelMps2,
          surroundingRearAccelMps2,
          surroundingFrontLeftAccelMps2,
          surroundingFrontRightAccelMps2,
          surroundingRearLeftAccelMps2,
          surroundingRearRightAccelMps2,
      )

  private val duplicateTickCompareColumnNames: List<String> =
      listOf(
          "surrounding_dist_front",
          "surrounding_dist_rear",
          "surrounding_dist_front_left",
          "surrounding_dist_front_right",
          "surrounding_dist_rear_left",
          "surrounding_dist_rear_right",
          "ego_speed_mps",
          "surrounding_front_speed_mps",
          "surrounding_rear_speed_mps",
          "surrounding_front_left_speed_mps",
          "surrounding_front_right_speed_mps",
          "surrounding_rear_left_speed_mps",
          "surrounding_rear_right_speed_mps",
          "ego_accel_mps2",
          "surrounding_front_accel_mps2",
          "surrounding_rear_accel_mps2",
          "surrounding_front_left_accel_mps2",
          "surrounding_front_right_accel_mps2",
          "surrounding_rear_left_accel_mps2",
          "surrounding_rear_right_accel_mps2",
      )

  /**
   * Loads [duplicateTickCompareColumns] (plus [id]) for every row as a compact column-major
   * snapshot for duplicate-tick analysis.
   *
   * Splits the read into [parallelism] concurrent queries, each bounded to an [chunkSizeRows]-wide
   * `id` range, instead of one `SELECT` over the whole table. This is necessary because the DB
   * connection is configured for Postgres's simple query protocol (see
   * [tools.aqua.stars.coverage.significance.db.DbBootstrap]), which has no server-side cursor:
   * without chunking, the JDBC driver must buffer the *entire* result client-side — one text-format
   * `byte[]` per column per row, each with its own JVM object overhead — before returning any of
   * it. For hundreds of millions of rows that inflates far past the raw data size (a single
   * unchunked 470M-row, 21-column read was observed to peak around 300GB of driver-side buffering
   * for ~40GB of actual data) and reliably triggers an `OutOfMemoryError`. Bounding each query to
   * [chunkSizeRows] rows keeps that transient buffering small regardless of total table size, and
   * running [parallelism] chunks concurrently keeps wall-clock time down and reports progress as
   * chunks complete (an unchunked read gives no feedback until the whole thing finishes).
   *
   * Rows are written into their destination array index via a shared, atomically-incremented
   * counter as each chunk's results stream in, so the (arbitrary) order chunks complete in doesn't
   * matter — every chunk writes to disjoint indices, and `Future.get()` on every chunk before
   * returning guarantees the writes are visible to the caller.
   *
   * @param chunkSizeRows Number of ids covered by each partitioned query.
   * @param parallelism Number of chunk queries to run concurrently. Must not exceed the configured
   *   HikariCP pool size (`DbBootstrap.DbConfig.maxPoolSize`) — this function does not hold any
   *   connection of its own open across the parallel load, so `maxPoolSize >= parallelism` is
   *   sufficient (no extra headroom needed for an ambient caller transaction).
   * @return [DuplicateTickColumns] holding row IDs and the compared columns.
   */
  fun buildDuplicateTickCompareColumns(
      chunkSizeRows: Int = 10_000_000,
      parallelism: Int = 8,
  ): DuplicateTickColumns {
    data class IdBounds(val rowCount: Int, val minId: Long, val maxId: Long)

    // Own short-lived transaction (rather than relying on an ambient one from the caller): if a
    // caller wrapped this whole call in `db { }`/`transaction { }`, that outer transaction would
    // otherwise hold a connection for the entire parallel load below, competing with the
    // [parallelism] worker connections against the same pool — see the [parallelism] KDoc.
    val bounds =
        transaction {
          TransactionManager.current().exec(
              "SELECT COUNT(*) AS row_count, MIN(id) AS min_id, MAX(id) AS max_id FROM metric_failed_monitors",
              explicitStatementType = StatementType.SELECT) { rs ->
                rs.next()
                val rowCount = rs.getLong("row_count")
                check(rowCount <= Int.MAX_VALUE) {
                  "$rowCount rows exceed the capacity of the in-memory column arrays"
                }
                IdBounds(
                    rowCount = rowCount.toInt(),
                    minId = rs.getLong("min_id"),
                    maxId = rs.getLong("max_id"))
              }
        } ?: error("Failed to read id bounds from metric_failed_monitors")

    val ids = LongArray(bounds.rowCount)
    val columns = Array(duplicateTickCompareColumns.size) { FloatArray(bounds.rowCount) }

    if (bounds.rowCount == 0) {
      return DuplicateTickColumns(
          ids = ids, columnNames = duplicateTickCompareColumnNames, columns = columns)
    }

    val chunkStarts = (bounds.minId..bounds.maxId step chunkSizeRows.toLong()).toList()
    val totalChunks = chunkStarts.size
    val completedChunks = AtomicInteger(0)
    val nextIndex = AtomicInteger(0)

    println("  Loading ${bounds.rowCount} rows in $totalChunks chunks ($parallelism at a time) ...")

    val pool = Executors.newFixedThreadPool(parallelism)
    try {
      val futures =
          chunkStarts.map { chunkStart ->
            val chunkEnd = minOf(chunkStart + chunkSizeRows - 1, bounds.maxId)
            pool.submit {
              transaction {
                // Fully qualified: bare `id` inside a transaction {} block would otherwise
                // resolve to Transaction.id (a String transaction identifier), not this table's
                // id column.
                select(MetricFailedMonitorsTable.id, *duplicateTickCompareColumns.toTypedArray())
                    .where {
                      (MetricFailedMonitorsTable.id greaterEq chunkStart) and
                          (MetricFailedMonitorsTable.id lessEq chunkEnd)
                    }
                    .forEach { row ->
                      val i = nextIndex.getAndIncrement()
                      ids[i] = row[MetricFailedMonitorsTable.id].value
                      for (c in duplicateTickCompareColumns.indices) {
                        columns[c][i] = row[duplicateTickCompareColumns[c]] ?: Float.NaN
                      }
                    }
              }
              val done = completedChunks.incrementAndGet()
              println("  Loaded chunk $done/$totalChunks (ids $chunkStart..$chunkEnd)")
            }
          }
      futures.forEach { it.get() }
    } finally {
      pool.shutdown()
    }

    check(nextIndex.get() == bounds.rowCount) {
      "Expected ${bounds.rowCount} rows but loaded ${nextIndex.get()} — was the table modified " +
          "concurrently while loading?"
    }

    return DuplicateTickColumns(
        ids = ids, columnNames = duplicateTickCompareColumnNames, columns = columns)
  }

  /**
   * Builds a mapping of scenario failures for each scenario instance.
   *
   * @return Mapping of scenario failures for each scenario instance.
   */
  fun buildFailedMonitorMapping(): List<ScenarioFailure> {
    val query =
        select(
            currentTSCInstance,
            mutant,
            startingScenarioConfiguration,
            monitorG0Failed,
            monitorG1Failed,
            monitorG2Failed,
            monitorG3Failed,
            monitorG4Failed,
            monitorI1Failed,
            monitorI2Failed)

    val result = mutableMapOf<Int, MutableMap<Int, MutableList<MutantFailures>>>()

    for (row in query) {
      val tscInstanceId = row[currentTSCInstance].value
      val scenarioInstanceId = row[startingScenarioConfiguration].value
      val mutantId = row[mutant].value
      val violations = row.toMonitorViolations()

      val scenarios = result.getOrPut(tscInstanceId) { mutableMapOf() }
      val mutants = scenarios.getOrPut(mutantId) { mutableListOf() }

      mutants += MutantFailures(mutantId = mutantId, violations = violations)
    }

    return result.map { (tscInstanceId, scenarios) ->
      ScenarioFailure(
          scenarioId = tscInstanceId,
          scenarioInstanceFailures =
              scenarios.map { (scenarioInstanceId, mutants) ->
                ScenarioInstanceFailures(scenarioInstanceId = scenarioInstanceId, mutants = mutants)
              })
    }
  }

  /**
   * Builds a mapping of failed mutants for each scenario instance.
   *
   * @return Mapping of failed mutants for each scenario instance.
   */
  fun buildFailedMutantsMapping(): List<MutantFailure> =
      select(
              startingScenarioConfiguration,
              mutant,
              tsc,
              currentTSCInstance,
              monitorG0Failed,
              monitorG1Failed,
              monitorG2Failed,
              monitorG3Failed,
              monitorG4Failed,
              monitorI1Failed,
              monitorI2Failed)
          .mapNotNull {
            val setOfMonitorViolations = it.toMonitorViolations().toSet()
            val monitorBitmask = setOfMonitorViolations.toBitmask()

            MutantFailure(
                tscId = it[tsc].value,
                currentTSCInstance = it[currentTSCInstance].value,
                startingScenarioConfigurationID = it[startingScenarioConfiguration].value,
                mutantID = it[mutant].value,
                monitorBitmask = monitorBitmask)
          }
          .toList()

  /**
   * Returns a mapping from each leaf node ID to the distinct scenario starting-configuration IDs
   * that have at least one tick classified into that leaf, using the most recent decision tree run.
   *
   * Returns an empty map if no decision tree run has been recorded yet.
   *
   * The result can be joined in-memory with [buildFailedMonitorMapping] to obtain a
   * [tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.LeafFailure] list for
   * leaf-stratified test-suite sampling.
   *
   * @return Map of `leaf_node_id` → list of `scenario_config_id` values.
   */
  fun buildLeafToScenarioConfigMapping(): Map<Int, List<Int>> {
    val latestRunId =
        DecisionTreeRunsTable.selectAll()
            .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(DecisionTreeRunsTable.id) ?: return emptyMap()

    return MetricFailedMonitorsTable.join(
            DecisionTreeLeafAssignmentsTable,
            JoinType.INNER,
            onColumn = MetricFailedMonitorsTable.id,
            otherColumn = DecisionTreeLeafAssignmentsTable.metricFailedMonitorId,
            additionalConstraint = { DecisionTreeLeafAssignmentsTable.runId eq latestRunId })
        .select(
            DecisionTreeLeafAssignmentsTable.leafNodeId,
            MetricFailedMonitorsTable.startingScenarioConfiguration)
        .withDistinct()
        .groupBy(
            { it[DecisionTreeLeafAssignmentsTable.leafNodeId] },
            { it[MetricFailedMonitorsTable.startingScenarioConfiguration].value },
        )
  }

  /**
   * The first [SQLException.getSQLState] found by walking this [Throwable]'s cause chain (Exposed
   * and the connection pool both wrap the original driver exception), or `null` if none is found.
   */
  internal tailrec fun Throwable?.sqlState(): String? =
      when {
        this == null -> null
        this is SQLException && this.sqlState != null -> this.sqlState
        else -> this.cause.sqlState()
      }

  /**
   * Whether [this] looks like the connection was severed by something *other* than the query
   * itself - PostgreSQL's "Operator Intervention" error class (SQLSTATE `57*`: e.g. `57P01`
   * admin_shutdown, `57P02` crash_shutdown, `57P03` cannot_connect_now). Seen in practice as the
   * `db` container (`docker-compose.yml`) being restarted mid-load: every connection open at that
   * moment gets `57P01` at once, not just one targeted backend - nothing a retry on a *new*
   * connection, made once the container is back up, can't recover from, unlike a genuine query
   * error.
   */
  internal fun Throwable.isTransientConnectionTermination(): Boolean =
      sqlState()?.startsWith("57") == true

  /**
   * Runs [block], retrying up to [maxAttempts] times (with an exponentially increasing
   * `[baseDelayMs] * 2^(attempt-1)` backoff) if it fails with [isTransientConnectionTermination].
   * Any other failure, or exhausting the retries, propagates immediately. Used per chunk in
   * [buildTickWiseNextTickMonitorViolations] so a `db` container restart doesn't fail a load that
   * may otherwise be most of the way done.
   *
   * The default budget (6 attempts, 5s doubling up to 80s - roughly 2.5 minutes total across all
   * waits) is sized for a *container* restart, not just a single severed connection: Postgres
   * shutting down, Docker recreating the container, and Postgres accepting connections again can
   * together take tens of seconds, during which every one of [parallelism]'s chunks will be
   * retrying/waiting at once.
   *
   * @param baseDelayMs Exposed as a parameter only so tests can shrink the backoff - production
   *   call sites should leave it at its default.
   */
  internal fun <T> withTransientConnectionRetry(
      chunkDescription: String,
      maxAttempts: Int = 6,
      baseDelayMs: Long = 5_000L,
      block: () -> T,
  ): T {
    var attempt = 1
    while (true) {
      try {
        return block()
      } catch (e: Exception) {
        if (!e.isTransientConnectionTermination() || attempt >= maxAttempts) throw e
        val delayMs = baseDelayMs * (1L shl (attempt - 1))
        println(
            "  WARNING: $chunkDescription lost its connection (SQLSTATE ${e.sqlState()}, attempt " +
                "$attempt/$maxAttempts) - retrying on a new connection in ${delayMs}ms: ${e.message}")
        Thread.sleep(delayMs)
        attempt++
      }
    }
  }

  /**
   * A run's [DecisionTreeLeafAssignmentChunksTable] rows, loaded once and indexed for O(1) per-tick
   * lookup - the in-memory alternative to joining against the [DecisionTreeLeafAssignmentsTable]
   * view per [buildTickWiseNextTickMonitorViolations] chunk.
   *
   * That view expands [DecisionTreeLeafAssignmentChunksTable] via `unnest(...) WITH ORDINALITY`,
   * computing its `metric_failed_monitor_id` join key on the fly - Postgres cannot use an index to
   * filter a computed column, so joining it with an `id BETWEEN ...` predicate (as each chunk needs
   * to) forces it to expand *every* chunk of the run first, same as documented on
   * [DecisionTreeLeafAssignmentsRepository.getByKey]. Paid once, that's fine; paid once per
   * [buildTickWiseNextTickMonitorViolations] chunk (141 times, in one real run), it turned a ~1
   * hour unchunked load into 7+ hours for 111 of those 141 chunks - the *chunking* was working as
   * intended, but every chunk was separately re-doing the full run's worth of unnest work.
   *
   * [DecisionTreeLeafAssignmentChunksTable] itself is tiny by comparison - one row per
   * [LEAF_ASSIGNMENT_CHUNK_SIZE] ids (~140K rows for 1.4 billion ticks) - so loading it whole,
   * once, costs nothing next to that.
   */
  internal class LeafAssignmentLookup(private val chunksByFirstId: Map<Long, ShortArray>) {

    /** The leaf node id assigned to [metricFailedMonitorId], or `null` if it has none. */
    fun leafNodeIdOrNull(metricFailedMonitorId: Long): Int? {
      val firstId = metricFailedMonitorId - metricFailedMonitorId % LEAF_ASSIGNMENT_CHUNK_SIZE
      val chunk = chunksByFirstId[firstId] ?: return null
      val leaf = chunk[(metricFailedMonitorId - firstId).toInt()]
      return if (leaf == NO_LEAF) null else leaf.toInt()
    }

    companion object {
      /**
       * Sentinel for "no assignment" in the primitive [ShortArray]s - avoids boxing 1.4 billion
       * nullable shorts just to represent what's usually a small number of id gaps. Internal
       * (rather than private) solely so tests can build [LeafAssignmentLookup] fixtures with it
       * without duplicating the magic number.
       */
      internal const val NO_LEAF: Short = Short.MIN_VALUE

      /** Loads every chunk recorded for [runId]. */
      fun load(runId: Int): LeafAssignmentLookup = transaction {
        val chunksByFirstId = HashMap<Long, ShortArray>()
        DecisionTreeLeafAssignmentChunksTable.selectAll()
            .where { DecisionTreeLeafAssignmentChunksTable.runId eq runId }
            .forEach { row ->
              val firstId = row[DecisionTreeLeafAssignmentChunksTable.firstMetricFailedMonitorId]
              val boxed = row[DecisionTreeLeafAssignmentChunksTable.leafNodeIds]
              chunksByFirstId[firstId] = ShortArray(boxed.size) { i -> boxed[i] ?: NO_LEAF }
            }
        LeafAssignmentLookup(chunksByFirstId)
      }
    }
  }

  /**
   * All rows from [MetricFailedMonitorsTable] projected to the four columns needed for sampling,
   * with leaf node assignments from [forRunId] (or the most recent decision tree run, if `null`)
   * attached via [LeafAssignmentLookup] - see its KDoc for why that, and not a SQL join, is used.
   *
   * If no decision tree run exists yet, all [NextTickPostEvaluationDatabaseEntry.leafNodeId] values
   * will be `null` (leaf-stratified sampling will produce empty groups).
   *
   * Splits the read into [parallelism] concurrent queries, each bounded to a [chunkSizeRows]-wide
   * `id` range, instead of one `SELECT` over the whole table - see
   * [buildDuplicateTickCompareColumns] for why an unchunked read of a table this size is both
   * single-threaded-slow *and* memory-dangerous: the configured Postgres connection has no
   * server-side cursor, so the JDBC driver buffers the *entire* result client-side before returning
   * any of it, regardless of parallelism. Each chunk writes its
   * [NextTickPostEvaluationDatabaseEntry] rows into a pre-sized array via a shared atomic index, so
   * the (arbitrary) order chunks complete in doesn't matter.
   *
   * Manages its own transactions rather than relying on an ambient one from the caller - like
   * [buildDuplicateTickCompareColumns], it must **not** be wrapped in `db {}`/`transaction {}` at
   * the call site, or that outer transaction would hold one connection idle for the entire parallel
   * load, on top of the [parallelism] worker connections below.
   *
   * @param chunkSizeRows Number of ids covered by each partitioned query.
   * @param parallelism Number of chunk queries to run concurrently. Must not exceed the configured
   *   HikariCP pool size (`DbBootstrap.DbConfig.maxPoolSize`) - see
   *   [buildDuplicateTickCompareColumns].
   *
   * Loaded once and reused across all sampling strategies.
   */
  fun buildTickWiseNextTickMonitorViolations(
      forRunId: EntityID<Int>? = null,
      chunkSizeRows: Int = 10_000_000,
      parallelism: Int = 8,
  ): List<NextTickPostEvaluationDatabaseEntry> {
    data class IdBounds(val rowCount: Int, val minId: Long, val maxId: Long)

    // Resolving the run id and reading the id bounds are both quick metadata-only reads, done up
    // front in one short-lived transaction - same reasoning as buildDuplicateTickCompareColumns.
    val (latestRunId, bounds) =
        transaction {
          val runId =
              forRunId
                  ?: DecisionTreeRunsTable.selectAll()
                      .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
                      .limit(1)
                      .firstOrNull()
                      ?.get(DecisionTreeRunsTable.id)
          val idBounds =
              TransactionManager.current().exec(
                  "SELECT COUNT(*) AS row_count, MIN(id) AS min_id, MAX(id) AS max_id FROM metric_failed_monitors",
                  explicitStatementType = StatementType.SELECT) { rs ->
                    rs.next()
                    val rowCount = rs.getLong("row_count")
                    check(rowCount <= Int.MAX_VALUE) {
                      "$rowCount rows exceed the capacity of the in-memory result array"
                    }
                    IdBounds(
                        rowCount = rowCount.toInt(),
                        minId = rs.getLong("min_id"),
                        maxId = rs.getLong("max_id"))
                  } ?: error("Failed to read id bounds from metric_failed_monitors")
          runId to idBounds
        }

    // One cheap, un-chunked load of the run's leaf assignments (see LeafAssignmentLookup's KDoc
    // for why this - not a per-chunk SQL join - is what makes leaf-node lookups below O(1)).
    val leafAssignmentLookup = latestRunId?.let { LeafAssignmentLookup.load(it.value) }

    // Physically holds nulls until every chunk below has written its slot, but is never read from
    // before that - same "claim non-null, fill before read" idiom as the final cast/return.
    @Suppress("UNCHECKED_CAST")
    val entries =
        arrayOfNulls<NextTickPostEvaluationDatabaseEntry>(bounds.rowCount)
            as Array<NextTickPostEvaluationDatabaseEntry>

    if (bounds.rowCount == 0) return entries.asList()

    val chunkStarts = (bounds.minId..bounds.maxId step chunkSizeRows.toLong()).toList()
    val totalChunks = chunkStarts.size
    val completedChunks = AtomicInteger(0)
    val nextIndex = AtomicInteger(0)

    println(
        "  Loading ${bounds.rowCount} ticks in $totalChunks chunks ($parallelism at a time) ...")

    val pool = Executors.newFixedThreadPool(parallelism)
    try {
      val futures =
          chunkStarts.map { chunkStart ->
            val chunkEnd = minOf(chunkStart + chunkSizeRows - 1, bounds.maxId)
            pool.submit {
              // Collected into a chunk-local list first, entirely inside the retried block: if the
              // connection dies partway through (see withTransientConnectionRetry) the retry simply
              // re-runs this whole query into a fresh local list, which is discarded on failure and
              // never touches `entries`/`nextIndex` - so a retried chunk can never double-write.
              // No join against DecisionTreeLeafAssignmentsTable here - see LeafAssignmentLookup's
              // KDoc: this is a plain, single-table, indexed-id-range SELECT, exactly the kind of
              // query chunking is supposed to speed up.
              val localRows =
                  withTransientConnectionRetry("chunk ids $chunkStart..$chunkEnd") {
                    transaction {
                      MetricFailedMonitorsTable.select(
                              MetricFailedMonitorsTable.id,
                              mutant,
                              nextTickMonitorG0Failed,
                              currentTSCInstance,
                              startingScenarioConfiguration)
                          .where {
                            (MetricFailedMonitorsTable.id greaterEq chunkStart) and
                                (MetricFailedMonitorsTable.id lessEq chunkEnd)
                          }
                          .map { row ->
                            NextTickPostEvaluationDatabaseEntry(
                                leafNodeId =
                                    leafAssignmentLookup?.leafNodeIdOrNull(
                                        row[MetricFailedMonitorsTable.id].value),
                                mutantId = row[mutant].value,
                                nextTickG0Failed = row[nextTickMonitorG0Failed],
                                tscInstanceId = row[currentTSCInstance].value,
                                scenarioConfigId = row[startingScenarioConfiguration].value,
                            )
                          }
                    }
                  }

              // Reserves a disjoint block of exactly localRows.size slots - done once, after the
              // chunk has already fully and successfully completed, so concurrent chunks (and any
              // retries of this one) can never collide or double-count.
              val startIndex = nextIndex.getAndAdd(localRows.size)
              for (j in localRows.indices) {
                entries[startIndex + j] = localRows[j]
              }

              val done = completedChunks.incrementAndGet()
              println("  Loaded chunk $done/$totalChunks (ids $chunkStart..$chunkEnd)")
            }
          }
      futures.forEach { it.get() }
    } finally {
      pool.shutdown()
    }

    check(nextIndex.get() == bounds.rowCount) {
      "Expected ${bounds.rowCount} ticks but loaded ${nextIndex.get()} — was the table modified " +
          "concurrently while loading?"
    }

    return entries.asList()
  }

  /**
   * Returns aggregated transition counts between TSC instances for the given TSC.
   *
   * Every row in metric_failed_monitors where [lastTickTSCInstance] is non-null counts as a
   * transition, including self-loops (instance unchanged). The diagonal (from == to) therefore
   * represents the most common case: the instance staying the same across consecutive ticks.
   *
   * @return One [TSCInstanceTransition] per distinct (from, to) instance pair.
   */
  fun buildTSCInstanceTransitions(tsc: TSC<*, *, *, *>): List<TSCInstanceTransition> {
    val tscEntryId = TSCsRepository.getByJson(tsc.getJsonString())?.id
    checkNotNull(tscEntryId) { "TSC entry not found for TSC: $tsc" }

    val sql =
        """
        SELECT
            m."last_tsc_instance_id"    AS from_id,
            m."current_tsc_instance_id" AS to_id,
            COUNT(*)                    AS total_count,
            SUM(CASE WHEN m."monitor_g0_Accidents_failed"                      THEN 1 ELSE 0 END) AS g0,
            SUM(CASE WHEN m."monitor_g1_SafeDistanceToPrecedingVehicle_failed" THEN 1 ELSE 0 END) AS g1,
            SUM(CASE WHEN m."monitor_g2_emergencyBraking_failed"               THEN 1 ELSE 0 END) AS g2,
            SUM(CASE WHEN m."monitor_g3_MaximumSpeedLimit_failed"              THEN 1 ELSE 0 END) AS g3,
            SUM(CASE WHEN m."monitor_g4_TrafficFlow_failed"                    THEN 1 ELSE 0 END) AS g4,
            SUM(CASE WHEN m."monitor_i1_Stopping_failed"                       THEN 1 ELSE 0 END) AS i1,
            SUM(CASE WHEN m."monitor_i2_DrivingFasterThenLeftTraffic_failed"   THEN 1 ELSE 0 END) AS i2
        FROM metric_failed_monitors m
        WHERE m."tsc_id" = $tscEntryId
            AND m."last_tsc_instance_id" IS NOT NULL
        GROUP BY m."last_tsc_instance_id", m."current_tsc_instance_id"
        """
            .trimIndent()

    return TransactionManager.current().exec(sql, explicitStatementType = StatementType.SELECT) { rs
      ->
      val result = mutableListOf<TSCInstanceTransition>()
      while (rs.next()) {
        result.add(
            TSCInstanceTransition(
                fromInstanceId = rs.getInt("from_id"),
                toInstanceId = rs.getInt("to_id"),
                totalCount = rs.getLong("total_count"),
                monitorCounts =
                    mapOf(
                        MonitorViolation.G0Accidents to rs.getLong("g0"),
                        MonitorViolation.G1SafeDistance to rs.getLong("g1"),
                        MonitorViolation.G2EmergencyBraking to rs.getLong("g2"),
                        MonitorViolation.G3MaximumSpeedLimit to rs.getLong("g3"),
                        MonitorViolation.G4TrafficFlow to rs.getLong("g4"),
                        MonitorViolation.I1Stopping to rs.getLong("i1"),
                        MonitorViolation.I2FasterThanLeftTraffic to rs.getLong("i2"),
                    )))
      }
      result
    } ?: emptyList()
  }
}
