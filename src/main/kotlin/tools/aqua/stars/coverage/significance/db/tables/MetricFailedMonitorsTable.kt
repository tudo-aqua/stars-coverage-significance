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

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceChangeData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceTransition
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toMonitorViolations
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
 * @property previouslyChangedTSCInstance Previously changed TSC instance.
 * @property previouslyChangedTSCInstanceTick Previously changed TSC instance tick.
 * @property tick TSC instance tick.
 * @property egoManeuverSpeed Ego maneuver speed.
 * @property egoManeuverLaneChange Ego maneuver lane change.
 * @property egoLane Lane the ego vehicle is currently on.
 * @property monitorG0Failed Whether monitor G0 failed.
 * @property monitorG1Failed Whether monitor G1 failed.
 * @property monitorG2Failed Whether monitor G2 failed.
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
 * @property surroundingDistFront Distance to nearest vehicle ahead on the same lane (m).
 * @property surroundingDistRear Distance to nearest vehicle behind on the same lane (m).
 * @property surroundingDistFrontLeft Distance to nearest vehicle ahead on the left lane (m).
 * @property surroundingDistFrontRight Distance to nearest vehicle ahead on the right lane (m).
 * @property surroundingDistRearLeft Distance to nearest vehicle behind on the left lane (m).
 * @property surroundingDistRearRight Distance to nearest vehicle behind on the right lane (m).
 * @property surroundingDistLeft Distance to nearest vehicle on the left lane, any position (m).
 * @property surroundingDistRight Distance to nearest vehicle on the right lane, any position (m).
 * @property createdAt Timestamp of creation.
 */
object MetricFailedMonitorsTable : IntIdTable("metric_failed_monitors") {
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
  val previouslyChangedTSCInstance =
      reference(
              name = "previously_changed_tsc_instance_id",
              foreign = TSCInstancesTable,
              onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
              onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
          .nullable()
  val previouslyChangedTSCInstanceTick = long("previously_changed_tsc_instance_tick").nullable()
  val tick = long("tick")
  val egoManeuverSpeed = float("ego_maneuver_speed").nullable()
  val egoManeuverLaneChange =
      enumerationByName("ego_maneuver_lane_change", 20, LaneChangeDirection::class).nullable()
  val egoLane = enumerationByName("ego_lane", 6, HighwayLane::class).nullable()
  val egoSpeedMps = float("ego_speed_mps").nullable()
  val egoAccelMps2 = float("ego_accel_mps2").nullable()
  val egoFrontBumperPosMeters = float("ego_front_bumper_pos_meters").nullable()
  val egoBackBumperPosMeters = float("ego_back_bumper_pos_meters").nullable()
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
  val surroundingFrontFrontBumperPosMeters =
      float("surrounding_front_front_bumper_pos_meters").nullable()
  val surroundingFrontBackBumperPosMeters =
      float("surrounding_front_back_bumper_pos_meters").nullable()
  val surroundingFrontAccelMps2 = float("surrounding_front_accel_mps2").nullable()
  val surroundingFrontSpeedDiffMps = float("surrounding_front_speed_diff_mps").nullable()
  val surroundingFrontAccelDiffMps2 = float("surrounding_front_accel_diff_mps2").nullable()
  val surroundingFrontTtcSeconds = float("surrounding_front_ttc_s").nullable()
  val surroundingFrontTgSeconds = float("surrounding_front_tg_s").nullable()
  val surroundingDistRear = float("surrounding_dist_rear").nullable()
  val surroundingRearSpeedMps = float("surrounding_rear_speed_mps").nullable()
  val surroundingRearFrontBumperPosMeters =
      float("surrounding_rear_front_bumper_pos_meters").nullable()
  val surroundingRearBackBumperPosMeters =
      float("surrounding_rear_back_bumper_pos_meters").nullable()
  val surroundingRearAccelMps2 = float("surrounding_rear_accel_mps2").nullable()
  val surroundingRearSpeedDiffMps = float("surrounding_rear_speed_diff_mps").nullable()
  val surroundingRearAccelDiffMps2 = float("surrounding_rear_accel_diff_mps2").nullable()
  val surroundingRearTtcSeconds = float("surrounding_rear_ttc_s").nullable()
  val surroundingRearTgSeconds = float("surrounding_rear_tg_s").nullable()
  val surroundingDistFrontLeft = float("surrounding_dist_front_left").nullable()
  val surroundingFrontLeftSpeedMps = float("surrounding_front_left_speed_mps").nullable()
  val surroundingFrontLeftFrontBumperPosMeters =
      float("surrounding_front_left_front_bumper_pos_meters").nullable()
  val surroundingFrontLeftBackBumperPosMeters =
      float("surrounding_front_left_back_bumper_pos_meters").nullable()
  val surroundingFrontLeftAccelMps2 = float("surrounding_front_left_accel_mps2").nullable()
  val surroundingFrontLeftSpeedDiffMps = float("surrounding_front_left_speed_diff_mps").nullable()
  val surroundingFrontLeftAccelDiffMps2 =
      float("surrounding_front_left_accel_diff_mps2").nullable()
  val surroundingFrontLeftTtcSeconds = float("surrounding_front_left_ttc_s").nullable()
  val surroundingFrontLeftTgSeconds = float("surrounding_front_left_tg_s").nullable()
  val surroundingDistFrontRight = float("surrounding_dist_front_right").nullable()
  val surroundingFrontRightSpeedMps = float("surrounding_front_right_speed_mps").nullable()
  val surroundingFrontRightFrontBumperPosMeters =
      float("surrounding_front_right_front_bumper_pos_meters").nullable()
  val surroundingFrontRightBackBumperPosMeters =
      float("surrounding_front_right_back_bumper_pos_meters").nullable()
  val surroundingFrontRightAccelMps2 = float("surrounding_front_right_accel_mps2").nullable()
  val surroundingFrontRightSpeedDiffMps =
      float("surrounding_front_right_speed_diff_mps").nullable()
  val surroundingFrontRightAccelDiffMps2 =
      float("surrounding_front_right_accel_diff_mps2").nullable()
  val surroundingFrontRightTtcSeconds = float("surrounding_front_right_ttc_s").nullable()
  val surroundingFrontRightTgSeconds = float("surrounding_front_right_tg_s").nullable()
  val surroundingDistRearLeft = float("surrounding_dist_rear_left").nullable()
  val surroundingRearLeftSpeedMps = float("surrounding_rear_left_speed_mps").nullable()
  val surroundingRearLeftFrontBumperPosMeters =
      float("surrounding_rear_left_front_bumper_pos_meters").nullable()
  val surroundingRearLeftBackBumperPosMeters =
      float("surrounding_rear_left_back_bumper_pos_meters").nullable()
  val surroundingRearLeftAccelMps2 = float("surrounding_rear_left_accel_mps2").nullable()
  val surroundingRearLeftSpeedDiffMps = float("surrounding_rear_left_speed_diff_mps").nullable()
  val surroundingRearLeftAccelDiffMps2 = float("surrounding_rear_left_accel_diff_mps2").nullable()
  val surroundingRearLeftTtcSeconds = float("surrounding_rear_left_ttc_s").nullable()
  val surroundingRearLeftTgSeconds = float("surrounding_rear_left_tg_s").nullable()
  val surroundingDistRearRight = float("surrounding_dist_rear_right").nullable()
  val surroundingRearRightSpeedMps = float("surrounding_rear_right_speed_mps").nullable()
  val surroundingRearRightFrontBumperPosMeters =
      float("surrounding_rear_right_front_bumper_pos_meters").nullable()
  val surroundingRearRightBackBumperPosMeters =
      float("surrounding_rear_right_back_bumper_pos_meters").nullable()
  val surroundingRearRightAccelMps2 = float("surrounding_rear_right_accel_mps2").nullable()
  val surroundingRearRightSpeedDiffMps = float("surrounding_rear_right_speed_diff_mps").nullable()
  val surroundingRearRightAccelDiffMps2 =
      float("surrounding_rear_right_accel_diff_mps2").nullable()
  val surroundingRearRightTtcSeconds = float("surrounding_rear_right_ttc_s").nullable()
  val surroundingRearRightTgSeconds = float("surrounding_rear_right_tg_s").nullable()
  val surroundingDistLeft = float("surrounding_dist_left").nullable()
  val surroundingLeftSpeedMps = float("surrounding_left_speed_mps").nullable()
  val surroundingLeftFrontBumperPosMeters =
      float("surrounding_left_front_bumper_pos_meters").nullable()
  val surroundingLeftBackBumperPosMeters =
      float("surrounding_left_back_bumper_pos_meters").nullable()
  val surroundingLeftAccelMps2 = float("surrounding_left_accel_mps2").nullable()
  val surroundingLeftSpeedDiffMps = float("surrounding_left_speed_diff_mps").nullable()
  val surroundingLeftAccelDiffMps2 = float("surrounding_left_accel_diff_mps2").nullable()
  val surroundingLeftTtcSeconds = float("surrounding_left_ttc_s").nullable()
  val surroundingLeftTgSeconds = float("surrounding_left_tg_s").nullable()
  val surroundingDistRight = float("surrounding_dist_right").nullable()
  val surroundingRightSpeedMps = float("surrounding_right_speed_mps").nullable()
  val surroundingRightFrontBumperPosMeters =
      float("surrounding_right_front_bumper_pos_meters").nullable()
  val surroundingRightBackBumperPosMeters =
      float("surrounding_right_back_bumper_pos_meters").nullable()
  val surroundingRightAccelMps2 = float("surrounding_right_accel_mps2").nullable()
  val surroundingRightSpeedDiffMps = float("surrounding_right_speed_diff_mps").nullable()
  val surroundingRightAccelDiffMps2 = float("surrounding_right_accel_diff_mps2").nullable()
  val surroundingRightTtcSeconds = float("surrounding_right_ttc_s").nullable()
  val surroundingRightTgSeconds = float("surrounding_right_tg_s").nullable()
  val createdAt = timestamp("created_at")

  init {
    index(true, tsc, run, startingScenarioConfiguration, mutant, tick)

    index(false, tsc)
    index(false, run)
    index(false, startingScenarioConfiguration)
    index(false, mutant)
    index(false, tick)
    index(false, monitorG0Failed)
    index(false, nextTickMonitorG0Failed)
  }

  /**
   * Builds a mapping of scenario failures for each scenario instance.
   *
   * @return Mapping of scenario failures for each scenario instance.
   */
  fun buildFailedMonitorMapping(): List<ScenarioFailure> {
    val failedMonitors = MetricFailedMonitorsTable
    val joinedWithTSCInstances =
        failedMonitors.join(
            otherTable = MetricStartingValidTSCInstancesTable,
            onColumn = startingScenarioConfiguration,
            otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
            joinType = JoinType.LEFT)

    val query =
        joinedWithTSCInstances.select(
            mutant,
            MetricStartingValidTSCInstancesTable.tscInstance,
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
      val tscInstanceId = row[MetricStartingValidTSCInstancesTable.tscInstance].value
      val scenarioInstanceId = row[startingScenarioConfiguration].value
      val mutantId = row[mutant].value
      val violations = row.toMonitorViolations()

      val scenarios = result.getOrPut(tscInstanceId) { mutableMapOf() }
      val mutants = scenarios.getOrPut(scenarioInstanceId) { mutableListOf() }

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
   * For each (mutant, scenarioConfiguration) pair, returns:
   * - the elapsed milliseconds from the scenario start until the TSC instance first changed, or
   *   null if no change was observed;
   * - the union of all monitors that failed from the scenario start up to (and including) the first
   *   TSC instance change, or across the entire observation window when no change occurred.
   *
   * All aggregation (MIN, BOOL_OR) is delegated to the database via a single CTE query so that only
   * one result row per pair is transferred to the application.
   *
   * @return One [TSCInstanceChangeData] per distinct (mutant, scenarioConfiguration) pair.
   */
  fun buildTSCInstanceChangeData(tsc: TSC<*, *, *, *>): List<TSCInstanceChangeData> {
    val tscEntryId = TSCsRepository.getByJson(tsc.getJsonString())?.id

    checkNotNull(tscEntryId) { "TSC entry not found for TSC: $tsc" }

    // Column names as stored in PostgreSQL (double-quoted to preserve the mixed-case names that
    // Exposed uses when generating the DDL).
    val sql =
        """
        WITH first_change AS (
            SELECT
                "mutant_id",
                "scenario_config_id",
                MIN("tick")                                  AS start_tick,
                MIN("previously_changed_tsc_instance_tick") AS first_change_tick
            FROM metric_failed_monitors
            WHERE "tsc_id" = $tscEntryId
            GROUP BY "mutant_id", "scenario_config_id"
        )
        SELECT
            f."mutant_id",
            f."scenario_config_id",
            fc.first_change_tick                             AS millis_until_change,
            BOOL_OR(f."monitor_g0_Accidents_failed")                           AS g0,
            BOOL_OR(f."monitor_g1_SafeDistanceToPrecedingVehicle_failed")      AS g1,
            BOOL_OR(f."monitor_g2_emergencyBraking_failed")                    AS g2,
            BOOL_OR(f."monitor_g3_MaximumSpeedLimit_failed")                   AS g3,
            BOOL_OR(f."monitor_g4_TrafficFlow_failed")                         AS g4,
            BOOL_OR(f."monitor_i1_Stopping_failed")                            AS i1,
            BOOL_OR(f."monitor_i2_DrivingFasterThenLeftTraffic_failed")        AS i2
        FROM metric_failed_monitors f
        JOIN first_change fc
            ON  f."mutant_id"         = fc."mutant_id"
            AND f."scenario_config_id" = fc."scenario_config_id"
            AND (fc.first_change_tick IS NULL OR f."tick" <= fc.first_change_tick)
        GROUP BY f."mutant_id", f."scenario_config_id", fc.first_change_tick, fc.start_tick
        """
            .trimIndent()

    return TransactionManager.current().exec(sql, explicitStatementType = StatementType.SELECT) { rs
      ->
      val result = mutableListOf<TSCInstanceChangeData>()
      while (rs.next()) {
        val rawMillis = rs.getLong("millis_until_change")
        val millisUntilChange = if (rs.wasNull()) null else rawMillis
        result.add(
            TSCInstanceChangeData(
                mutantId = rs.getInt("mutant_id"),
                scenarioConfigId = rs.getInt("scenario_config_id"),
                millisUntilFirstChange = millisUntilChange,
                failedMonitorsUntilChange =
                    buildSet {
                      if (rs.getBoolean("g0")) add(MonitorViolation.G0Accidents)
                      if (rs.getBoolean("g1")) add(MonitorViolation.G1SafeDistance)
                      if (rs.getBoolean("g2")) add(MonitorViolation.G2EmergencyBraking)
                      if (rs.getBoolean("g3")) add(MonitorViolation.G3MaximumSpeedLimit)
                      if (rs.getBoolean("g4")) add(MonitorViolation.G4TrafficFlow)
                      if (rs.getBoolean("i1")) add(MonitorViolation.I1Stopping)
                      if (rs.getBoolean("i2")) add(MonitorViolation.I2FasterThanLeftTraffic)
                    }))
      }
      result
    } ?: emptyList()
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
