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

package tools.aqua.stars.coverage.significance.db.repositories

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsertReturning
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

/** Repository for managing [MetricFailedMonitorsEntry] in the [MetricFailedMonitorsTable]. */
object MetricFailedMonitorsRepository {

  /**
   * Retrieves a [MetricFailedMonitorsEntry] by its unique identifier.
   *
   * @param id Unique identifier of the metric entry.
   * @return The corresponding [MetricFailedMonitorsEntry] or null if not found.
   */
  fun getById(id: Int): MetricFailedMonitorsEntry? = db {
    MetricFailedMonitorsTable.selectAll()
        .where { MetricFailedMonitorsTable.id eq id }
        .limit(1)
        .singleOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all [MetricFailedMonitorsEntry]s that satisfy the given predicate.
   *
   * @param predicate Predicate to filter the results.
   */
  fun callPredicate(predicate: Op<Boolean>) = db {
    MetricFailedMonitorsTable.selectAll().where(predicate)
  }

  /**
   * Retrieves all [MetricFailedMonitorsEntry]s.
   *
   * @return All [MetricFailedMonitorsEntry]s as a [Query].
   */
  fun getAll(): Query = db { MetricFailedMonitorsTable.selectAll() }

  /** Retrieves the count of [MetricFailedMonitorsEntry]s. */
  fun count(): Long = db { MetricFailedMonitorsTable.selectAll().count() }

  /** Retrieves the count of failed monitor entries. */
  fun countFailures(): Long = db {
    MetricFailedMonitorsTable.selectAll()
        .where { MetricFailedMonitorsTable.monitorG0Failed eq true }
        .count()
  }

  /**
   * Retrieves a [MetricFailedMonitorsEntry] by its unique key: (run, tsc, scenario_config, mutant).
   *
   * @param runId Unique identifier of the evaluation run.
   * @param tscId Unique identifier of the TSC.
   * @param scenarioConfigId Unique identifier of the scenario starting configuration.
   * @param mutantId Unique identifier of the mutant.
   * @return The corresponding [MetricFailedMonitorsEntry] or null if not found.
   */
  fun getByKey(
      runId: Int,
      tscId: Int,
      scenarioConfigId: Int,
      mutantId: Int
  ): MetricFailedMonitorsEntry? = db {
    MetricFailedMonitorsTable.selectAll()
        .where {
          (MetricFailedMonitorsTable.run eq runId) and
              (MetricFailedMonitorsTable.tsc eq tscId) and
              (MetricFailedMonitorsTable.startingScenarioConfiguration eq scenarioConfigId) and
              (MetricFailedMonitorsTable.mutant eq mutantId)
        }
        .limit(1)
        .singleOrNull()
        ?.toEntry()
  }

  /**
   * Inserts multiple [MetricFailedMonitorsEntry] entries in a batch operation.
   *
   * @param entries List of [MetricFailedMonitorsEntry] to insert. Each entry's `id` must be null.
   * @param ignore If `true`, rows conflicting with an existing (tsc, run, scenarioConfig, mutant,
   *   tick) entry are silently skipped instead of throwing. A chunk job writes this table in one
   *   batch insert (via `FailedMonitorsMetric.postEvaluate`) followed by further DB work (tick
   *   differences, marking the job done); if that later work fails, the whole job gets requeued and
   *   re-run from scratch — deterministically recomputing and re-inserting the exact same rows this
   *   batch insert already committed. Without `ignore = true`, that retry fails on the unique
   *   constraint instead of silently no-op'ing over already-written rows.
   */
  fun batchInsert(entries: List<MetricFailedMonitorsEntry>, ignore: Boolean = false) = db {
    if (entries.isEmpty()) return@db

    MetricFailedMonitorsTable.batchInsert(entries, ignore = ignore) { e ->
      this[MetricFailedMonitorsTable.run] = e.runId
      this[MetricFailedMonitorsTable.tsc] = e.tscId
      this[MetricFailedMonitorsTable.mutant] = e.mutantId
      this[MetricFailedMonitorsTable.startingScenarioConfiguration] = e.scenarioConfigId

      this[MetricFailedMonitorsTable.currentTSCInstance] = e.currentTSCInstanceId
      this[MetricFailedMonitorsTable.lastTickTSCInstance] = e.lastTickTSCInstanceId
      this[MetricFailedMonitorsTable.previouslyChangedTSCInstance] = e.previousTSCInstanceId
      this[MetricFailedMonitorsTable.previouslyChangedTSCInstanceTick] = e.previousTSCInstanceTick
      this[MetricFailedMonitorsTable.tick] = e.tick
      this[MetricFailedMonitorsTable.egoManeuverSpeed] = e.egoManeuverSpeed
      this[MetricFailedMonitorsTable.egoManeuverLaneChange] = e.egoManeuverLangeChange
      this[MetricFailedMonitorsTable.egoLane] = e.egoLane
      this[MetricFailedMonitorsTable.egoSpeedMps] = e.egoSpeedMps
      this[MetricFailedMonitorsTable.egoAccelMps2] = e.egoAccelMps2
      this[MetricFailedMonitorsTable.egoFrontBumperPosMeters] = e.egoFrontBumperPosMeters
      this[MetricFailedMonitorsTable.egoBackBumperPosMeters] = e.egoBackBumperPosMeters

      this[MetricFailedMonitorsTable.monitorG0Failed] = e.monitorG0Failed
      this[MetricFailedMonitorsTable.monitorG1Failed] = e.monitorG1Failed
      this[MetricFailedMonitorsTable.monitorG2Failed] = e.monitorG2Failed
      this[MetricFailedMonitorsTable.monitorG3Failed] = e.monitorG3Failed
      this[MetricFailedMonitorsTable.monitorG4Failed] = e.monitorG4Failed
      this[MetricFailedMonitorsTable.monitorI1Failed] = e.monitorI1Failed
      this[MetricFailedMonitorsTable.monitorI2Failed] = e.monitorI2Failed

      this[MetricFailedMonitorsTable.nextTickMonitorG0Failed] = e.nextTickMonitorG0Failed
      this[MetricFailedMonitorsTable.nextTickMonitorG1Failed] = e.nextTickMonitorG1Failed
      this[MetricFailedMonitorsTable.nextTickMonitorG2Failed] = e.nextTickMonitorG2Failed
      this[MetricFailedMonitorsTable.nextTickMonitorG3Failed] = e.nextTickMonitorG3Failed
      this[MetricFailedMonitorsTable.nextTickMonitorG4Failed] = e.nextTickMonitorG4Failed
      this[MetricFailedMonitorsTable.nextTickMonitorI1Failed] = e.nextTickMonitorI1Failed
      this[MetricFailedMonitorsTable.nextTickMonitorI2Failed] = e.nextTickMonitorI2Failed

      this[MetricFailedMonitorsTable.surroundingDistFront] = e.surroundingDistFront
      this[MetricFailedMonitorsTable.surroundingFrontSpeedMps] = e.surroundingFrontSpeedMps
      this[MetricFailedMonitorsTable.surroundingFrontFrontBumperPosMeters] =
          e.surroundingFrontFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontBackBumperPosMeters] =
          e.surroundingFrontBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontAccelMps2] = e.surroundingFrontAccelMps2
      this[MetricFailedMonitorsTable.surroundingFrontSpeedDiffMps] = e.surroundingFrontSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingFrontAccelDiffMps2] =
          e.surroundingFrontAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingFrontTtcSeconds] = e.surroundingFrontTtcSeconds
      this[MetricFailedMonitorsTable.surroundingFrontTgSeconds] = e.surroundingFrontTgSeconds

      this[MetricFailedMonitorsTable.surroundingDistRear] = e.surroundingDistRear
      this[MetricFailedMonitorsTable.surroundingRearSpeedMps] = e.surroundingRearSpeedMps
      this[MetricFailedMonitorsTable.surroundingRearFrontBumperPosMeters] =
          e.surroundingRearFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearBackBumperPosMeters] =
          e.surroundingRearBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearAccelMps2] = e.surroundingRearAccelMps2
      this[MetricFailedMonitorsTable.surroundingRearSpeedDiffMps] = e.surroundingRearSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingRearAccelDiffMps2] = e.surroundingRearAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingRearTtcSeconds] = e.surroundingRearTtcSeconds
      this[MetricFailedMonitorsTable.surroundingRearTgSeconds] = e.surroundingRearTgSeconds

      this[MetricFailedMonitorsTable.surroundingDistFrontLeft] = e.surroundingDistFrontLeft
      this[MetricFailedMonitorsTable.surroundingFrontLeftSpeedMps] = e.surroundingFrontLeftSpeedMps
      this[MetricFailedMonitorsTable.surroundingFrontLeftFrontBumperPosMeters] =
          e.surroundingFrontLeftFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontLeftBackBumperPosMeters] =
          e.surroundingFrontLeftBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontLeftAccelMps2] =
          e.surroundingFrontLeftAccelMps2
      this[MetricFailedMonitorsTable.surroundingFrontLeftSpeedDiffMps] =
          e.surroundingFrontLeftSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingFrontLeftAccelDiffMps2] =
          e.surroundingFrontLeftAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingFrontLeftTtcSeconds] =
          e.surroundingFrontLeftTtcSeconds
      this[MetricFailedMonitorsTable.surroundingFrontLeftTgSeconds] =
          e.surroundingFrontLeftTgSeconds

      this[MetricFailedMonitorsTable.surroundingDistFrontRight] = e.surroundingDistFrontRight
      this[MetricFailedMonitorsTable.surroundingFrontRightSpeedMps] =
          e.surroundingFrontRightSpeedMps
      this[MetricFailedMonitorsTable.surroundingFrontRightFrontBumperPosMeters] =
          e.surroundingFrontRightFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontRightBackBumperPosMeters] =
          e.surroundingFrontRightBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingFrontRightAccelMps2] =
          e.surroundingFrontRightAccelMps2
      this[MetricFailedMonitorsTable.surroundingFrontRightSpeedDiffMps] =
          e.surroundingFrontRightSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingFrontRightAccelDiffMps2] =
          e.surroundingFrontRightAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingFrontRightTtcSeconds] =
          e.surroundingFrontRightTtcSeconds
      this[MetricFailedMonitorsTable.surroundingFrontRightTgSeconds] =
          e.surroundingFrontRightTgSeconds

      this[MetricFailedMonitorsTable.surroundingDistRearLeft] = e.surroundingDistRearLeft
      this[MetricFailedMonitorsTable.surroundingRearLeftSpeedMps] = e.surroundingRearLeftSpeedMps
      this[MetricFailedMonitorsTable.surroundingRearLeftFrontBumperPosMeters] =
          e.surroundingRearLeftFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearLeftBackBumperPosMeters] =
          e.surroundingRearLeftBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearLeftAccelMps2] = e.surroundingRearLeftAccelMps2
      this[MetricFailedMonitorsTable.surroundingRearLeftSpeedDiffMps] =
          e.surroundingRearLeftSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingRearLeftAccelDiffMps2] =
          e.surroundingRearLeftAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingRearLeftTtcSeconds] =
          e.surroundingRearLeftTtcSeconds
      this[MetricFailedMonitorsTable.surroundingRearLeftTgSeconds] = e.surroundingRearLeftTgSeconds

      this[MetricFailedMonitorsTable.surroundingDistRearRight] = e.surroundingDistRearRight
      this[MetricFailedMonitorsTable.surroundingRearRightSpeedMps] = e.surroundingRearRightSpeedMps
      this[MetricFailedMonitorsTable.surroundingRearRightFrontBumperPosMeters] =
          e.surroundingRearRightFrontBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearRightBackBumperPosMeters] =
          e.surroundingRearRightBackBumperPosMeters
      this[MetricFailedMonitorsTable.surroundingRearRightAccelMps2] =
          e.surroundingRearRightAccelMps2
      this[MetricFailedMonitorsTable.surroundingRearRightSpeedDiffMps] =
          e.surroundingRearRightSpeedDiffMps
      this[MetricFailedMonitorsTable.surroundingRearRightAccelDiffMps2] =
          e.surroundingRearRightAccelDiffMps2
      this[MetricFailedMonitorsTable.surroundingRearRightTtcSeconds] =
          e.surroundingRearRightTtcSeconds
      this[MetricFailedMonitorsTable.surroundingRearRightTgSeconds] =
          e.surroundingRearRightTgSeconds

      this[MetricFailedMonitorsTable.collisionTimeSeconds] = e.collisionTimeSeconds
      this[MetricFailedMonitorsTable.collisionType] = e.collisionType
      this[MetricFailedMonitorsTable.collisionLane] = e.collisionLane
      this[MetricFailedMonitorsTable.collisionPositionOnLaneMeters] =
          e.collisionPositionOnLaneMeters
      this[MetricFailedMonitorsTable.collisionColliderVehicleId] = e.collisionColliderVehicleId
      this[MetricFailedMonitorsTable.collisionColliderLane] = e.collisionColliderLane
      this[MetricFailedMonitorsTable.collisionColliderSpeedMps] = e.collisionColliderSpeedMps
      this[MetricFailedMonitorsTable.collisionColliderAccelMps2] = e.collisionColliderAccelMps2
      this[MetricFailedMonitorsTable.collisionColliderFrontBumperPosMeters] =
          e.collisionColliderFrontBumperPosMeters
      this[MetricFailedMonitorsTable.collisionColliderBackBumperPosMeters] =
          e.collisionColliderBackBumperPosMeters
      this[MetricFailedMonitorsTable.collisionVictimVehicleId] = e.collisionVictimVehicleId
      this[MetricFailedMonitorsTable.collisionVictimLane] = e.collisionVictimLane
      this[MetricFailedMonitorsTable.collisionVictimSpeedMps] = e.collisionVictimSpeedMps
      this[MetricFailedMonitorsTable.collisionVictimAccelMps2] = e.collisionVictimAccelMps2
      this[MetricFailedMonitorsTable.collisionVictimFrontBumperPosMeters] =
          e.collisionVictimFrontBumperPosMeters
      this[MetricFailedMonitorsTable.collisionVictimBackBumperPosMeters] =
          e.collisionVictimBackBumperPosMeters

      this[MetricFailedMonitorsTable.allVehiclesJson] = e.allVehiclesJson

      this[MetricFailedMonitorsTable.createdAt] = e.createdAt
    }
  }

  /**
   * Inserts a new row and returns the canonical DB state (read-back).
   *
   * @param entry The [MetricFailedMonitorsEntry] to insert. Its `id` must be null.
   * @return The inserted [MetricFailedMonitorsEntry] with the generated `id`.
   */
  fun insert(entry: MetricFailedMonitorsEntry): MetricFailedMonitorsEntry = db {
    require(entry.id == null) { "insert() expects entry.id == null. Use upsert() otherwise." }

    val newId =
        MetricFailedMonitorsTable.insertAndGetId { row ->
              row[run] = entry.runId
              row[tsc] = entry.tscId
              row[startingScenarioConfiguration] = entry.scenarioConfigId
              row[mutant] = entry.mutantId

              row[currentTSCInstance] = entry.currentTSCInstanceId
              row[lastTickTSCInstance] = entry.lastTickTSCInstanceId
              row[previouslyChangedTSCInstance] = entry.previousTSCInstanceId
              row[previouslyChangedTSCInstanceTick] = entry.previousTSCInstanceTick
              row[tick] = entry.tick
              row[egoManeuverSpeed] = entry.egoManeuverSpeed
              row[egoManeuverLaneChange] = entry.egoManeuverLangeChange
              row[egoLane] = entry.egoLane
              row[egoSpeedMps] = entry.egoSpeedMps
              row[egoAccelMps2] = entry.egoAccelMps2
              row[egoFrontBumperPosMeters] = entry.egoFrontBumperPosMeters
              row[egoBackBumperPosMeters] = entry.egoBackBumperPosMeters

              row[monitorG0Failed] = entry.monitorG0Failed
              row[monitorG1Failed] = entry.monitorG1Failed
              row[monitorG2Failed] = entry.monitorG2Failed
              row[monitorG3Failed] = entry.monitorG3Failed
              row[monitorG4Failed] = entry.monitorG4Failed
              row[monitorI1Failed] = entry.monitorI1Failed
              row[monitorI2Failed] = entry.monitorI2Failed

              row[nextTickMonitorG0Failed] = entry.nextTickMonitorG0Failed
              row[nextTickMonitorG1Failed] = entry.nextTickMonitorG1Failed
              row[nextTickMonitorG2Failed] = entry.nextTickMonitorG2Failed
              row[nextTickMonitorG3Failed] = entry.nextTickMonitorG3Failed
              row[nextTickMonitorG4Failed] = entry.nextTickMonitorG4Failed
              row[nextTickMonitorI1Failed] = entry.nextTickMonitorI1Failed
              row[nextTickMonitorI2Failed] = entry.nextTickMonitorI2Failed

              row[surroundingDistFront] = entry.surroundingDistFront
              row[surroundingFrontSpeedMps] = entry.surroundingFrontSpeedMps
              row[surroundingFrontFrontBumperPosMeters] = entry.surroundingFrontFrontBumperPosMeters
              row[surroundingFrontBackBumperPosMeters] = entry.surroundingFrontBackBumperPosMeters
              row[surroundingFrontAccelMps2] = entry.surroundingFrontAccelMps2
              row[surroundingFrontSpeedDiffMps] = entry.surroundingFrontSpeedDiffMps
              row[surroundingFrontAccelDiffMps2] = entry.surroundingFrontAccelDiffMps2
              row[surroundingFrontTtcSeconds] = entry.surroundingFrontTtcSeconds
              row[surroundingFrontTgSeconds] = entry.surroundingFrontTgSeconds

              row[surroundingDistRear] = entry.surroundingDistRear
              row[surroundingRearSpeedMps] = entry.surroundingRearSpeedMps
              row[surroundingRearFrontBumperPosMeters] = entry.surroundingRearFrontBumperPosMeters
              row[surroundingRearBackBumperPosMeters] = entry.surroundingRearBackBumperPosMeters
              row[surroundingRearAccelMps2] = entry.surroundingRearAccelMps2
              row[surroundingRearSpeedDiffMps] = entry.surroundingRearSpeedDiffMps
              row[surroundingRearAccelDiffMps2] = entry.surroundingRearAccelDiffMps2
              row[surroundingRearTtcSeconds] = entry.surroundingRearTtcSeconds
              row[surroundingRearTgSeconds] = entry.surroundingRearTgSeconds

              row[surroundingDistFrontLeft] = entry.surroundingDistFrontLeft
              row[surroundingFrontLeftSpeedMps] = entry.surroundingFrontLeftSpeedMps
              row[surroundingFrontLeftFrontBumperPosMeters] =
                  entry.surroundingFrontLeftFrontBumperPosMeters
              row[surroundingFrontLeftBackBumperPosMeters] =
                  entry.surroundingFrontLeftBackBumperPosMeters
              row[surroundingFrontLeftAccelMps2] = entry.surroundingFrontLeftAccelMps2
              row[surroundingFrontLeftSpeedDiffMps] = entry.surroundingFrontLeftSpeedDiffMps
              row[surroundingFrontLeftAccelDiffMps2] = entry.surroundingFrontLeftAccelDiffMps2
              row[surroundingFrontLeftTtcSeconds] = entry.surroundingFrontLeftTtcSeconds
              row[surroundingFrontLeftTgSeconds] = entry.surroundingFrontLeftTgSeconds

              row[surroundingDistFrontRight] = entry.surroundingDistFrontRight
              row[surroundingFrontRightSpeedMps] = entry.surroundingFrontRightSpeedMps
              row[surroundingFrontRightFrontBumperPosMeters] =
                  entry.surroundingFrontRightFrontBumperPosMeters
              row[surroundingFrontRightBackBumperPosMeters] =
                  entry.surroundingFrontRightBackBumperPosMeters
              row[surroundingFrontRightAccelMps2] = entry.surroundingFrontRightAccelMps2
              row[surroundingFrontRightSpeedDiffMps] = entry.surroundingFrontRightSpeedDiffMps
              row[surroundingFrontRightAccelDiffMps2] = entry.surroundingFrontRightAccelDiffMps2
              row[surroundingFrontRightTtcSeconds] = entry.surroundingFrontRightTtcSeconds
              row[surroundingFrontRightTgSeconds] = entry.surroundingFrontRightTgSeconds

              row[surroundingDistRearLeft] = entry.surroundingDistRearLeft
              row[surroundingRearLeftSpeedMps] = entry.surroundingRearLeftSpeedMps
              row[surroundingRearLeftFrontBumperPosMeters] =
                  entry.surroundingRearLeftFrontBumperPosMeters
              row[surroundingRearLeftBackBumperPosMeters] =
                  entry.surroundingRearLeftBackBumperPosMeters
              row[surroundingRearLeftAccelMps2] = entry.surroundingRearLeftAccelMps2
              row[surroundingRearLeftSpeedDiffMps] = entry.surroundingRearLeftSpeedDiffMps
              row[surroundingRearLeftAccelDiffMps2] = entry.surroundingRearLeftAccelDiffMps2
              row[surroundingRearLeftTtcSeconds] = entry.surroundingRearLeftTtcSeconds
              row[surroundingRearLeftTgSeconds] = entry.surroundingRearLeftTgSeconds

              row[surroundingDistRearRight] = entry.surroundingDistRearRight
              row[surroundingRearRightSpeedMps] = entry.surroundingRearRightSpeedMps
              row[surroundingRearRightFrontBumperPosMeters] =
                  entry.surroundingRearRightFrontBumperPosMeters
              row[surroundingRearRightBackBumperPosMeters] =
                  entry.surroundingRearRightBackBumperPosMeters
              row[surroundingRearRightAccelMps2] = entry.surroundingRearRightAccelMps2
              row[surroundingRearRightSpeedDiffMps] = entry.surroundingRearRightSpeedDiffMps
              row[surroundingRearRightAccelDiffMps2] = entry.surroundingRearRightAccelDiffMps2
              row[surroundingRearRightTtcSeconds] = entry.surroundingRearRightTtcSeconds
              row[surroundingRearRightTgSeconds] = entry.surroundingRearRightTgSeconds

              row[collisionTimeSeconds] = entry.collisionTimeSeconds
              row[collisionType] = entry.collisionType
              row[collisionLane] = entry.collisionLane
              row[collisionPositionOnLaneMeters] = entry.collisionPositionOnLaneMeters
              row[collisionColliderVehicleId] = entry.collisionColliderVehicleId
              row[collisionColliderLane] = entry.collisionColliderLane
              row[collisionColliderSpeedMps] = entry.collisionColliderSpeedMps
              row[collisionColliderAccelMps2] = entry.collisionColliderAccelMps2
              row[collisionColliderFrontBumperPosMeters] =
                  entry.collisionColliderFrontBumperPosMeters
              row[collisionColliderBackBumperPosMeters] = entry.collisionColliderBackBumperPosMeters
              row[collisionVictimVehicleId] = entry.collisionVictimVehicleId
              row[collisionVictimLane] = entry.collisionVictimLane
              row[collisionVictimSpeedMps] = entry.collisionVictimSpeedMps
              row[collisionVictimAccelMps2] = entry.collisionVictimAccelMps2
              row[collisionVictimFrontBumperPosMeters] = entry.collisionVictimFrontBumperPosMeters
              row[collisionVictimBackBumperPosMeters] = entry.collisionVictimBackBumperPosMeters

              row[allVehiclesJson] = entry.allVehiclesJson

              row[createdAt] = entry.createdAt
            }
            .value

    getById(newId) ?: error("Inserted MetricFailedMonitorsEntry not found (id=$newId).")
  }

  /**
   * Inserts or updates a row and returns the canonical DB state (read-back).
   *
   * @param entry The [MetricFailedMonitorsEntry] to upsert. Its `id` must be null.
   * @return The upserted [MetricFailedMonitorsEntry] with the generated `id` (if inserted) or
   *   existing `id` (if updated).
   * @throws IllegalArgumentException if `entry.id` is not null.
   * @throws IllegalStateException if the upserted entry cannot be retrieved after the operation
   */
  fun upsert(entry: MetricFailedMonitorsEntry): MetricFailedMonitorsEntry = db {
    require(entry.id == null) { "upsert() expects entry.id == null." }

    val row =
        MetricFailedMonitorsTable.upsertReturning(
                keys =
                    arrayOf(
                        MetricFailedMonitorsTable.tsc,
                        MetricFailedMonitorsTable.run,
                        MetricFailedMonitorsTable.startingScenarioConfiguration,
                        MetricFailedMonitorsTable.mutant,
                    )) { st ->
                  st[run] = entry.runId
                  st[tsc] = entry.tscId
                  st[startingScenarioConfiguration] = entry.scenarioConfigId
                  st[mutant] = entry.mutantId

                  st[currentTSCInstance] = entry.currentTSCInstanceId
                  st[lastTickTSCInstance] = entry.lastTickTSCInstanceId
                  st[previouslyChangedTSCInstance] = entry.previousTSCInstanceId
                  st[previouslyChangedTSCInstanceTick] = entry.previousTSCInstanceTick
                  st[tick] = entry.tick
                  st[egoManeuverSpeed] = entry.egoManeuverSpeed
                  st[egoManeuverLaneChange] = entry.egoManeuverLangeChange
                  st[egoLane] = entry.egoLane
                  st[egoSpeedMps] = entry.egoSpeedMps
                  st[egoAccelMps2] = entry.egoAccelMps2
                  st[egoFrontBumperPosMeters] = entry.egoFrontBumperPosMeters
                  st[egoBackBumperPosMeters] = entry.egoBackBumperPosMeters

                  st[monitorG0Failed] = entry.monitorG0Failed
                  st[monitorG1Failed] = entry.monitorG1Failed
                  st[monitorG2Failed] = entry.monitorG2Failed
                  st[monitorG3Failed] = entry.monitorG3Failed
                  st[monitorG4Failed] = entry.monitorG4Failed
                  st[monitorI1Failed] = entry.monitorI1Failed
                  st[monitorI2Failed] = entry.monitorI2Failed

                  st[nextTickMonitorG0Failed] = entry.nextTickMonitorG0Failed
                  st[nextTickMonitorG1Failed] = entry.nextTickMonitorG1Failed
                  st[nextTickMonitorG2Failed] = entry.nextTickMonitorG2Failed
                  st[nextTickMonitorG3Failed] = entry.nextTickMonitorG3Failed
                  st[nextTickMonitorG4Failed] = entry.nextTickMonitorG4Failed
                  st[nextTickMonitorI1Failed] = entry.nextTickMonitorI1Failed
                  st[nextTickMonitorI2Failed] = entry.nextTickMonitorI2Failed

                  st[surroundingDistFront] = entry.surroundingDistFront
                  st[surroundingFrontSpeedMps] = entry.surroundingFrontSpeedMps
                  st[surroundingFrontFrontBumperPosMeters] =
                      entry.surroundingFrontFrontBumperPosMeters
                  st[surroundingFrontBackBumperPosMeters] =
                      entry.surroundingFrontBackBumperPosMeters
                  st[surroundingFrontAccelMps2] = entry.surroundingFrontAccelMps2
                  st[surroundingFrontSpeedDiffMps] = entry.surroundingFrontSpeedDiffMps
                  st[surroundingFrontAccelDiffMps2] = entry.surroundingFrontAccelDiffMps2
                  st[surroundingFrontTtcSeconds] = entry.surroundingFrontTtcSeconds
                  st[surroundingFrontTgSeconds] = entry.surroundingFrontTgSeconds

                  st[surroundingDistRear] = entry.surroundingDistRear
                  st[surroundingRearSpeedMps] = entry.surroundingRearSpeedMps
                  st[surroundingRearFrontBumperPosMeters] =
                      entry.surroundingRearFrontBumperPosMeters
                  st[surroundingRearBackBumperPosMeters] = entry.surroundingRearBackBumperPosMeters
                  st[surroundingRearAccelMps2] = entry.surroundingRearAccelMps2
                  st[surroundingRearSpeedDiffMps] = entry.surroundingRearSpeedDiffMps
                  st[surroundingRearAccelDiffMps2] = entry.surroundingRearAccelDiffMps2
                  st[surroundingRearTtcSeconds] = entry.surroundingRearTtcSeconds
                  st[surroundingRearTgSeconds] = entry.surroundingRearTgSeconds

                  st[surroundingDistFrontLeft] = entry.surroundingDistFrontLeft
                  st[surroundingFrontLeftSpeedMps] = entry.surroundingFrontLeftSpeedMps
                  st[surroundingFrontLeftFrontBumperPosMeters] =
                      entry.surroundingFrontLeftFrontBumperPosMeters
                  st[surroundingFrontLeftBackBumperPosMeters] =
                      entry.surroundingFrontLeftBackBumperPosMeters
                  st[surroundingFrontLeftAccelMps2] = entry.surroundingFrontLeftAccelMps2
                  st[surroundingFrontLeftSpeedDiffMps] = entry.surroundingFrontLeftSpeedDiffMps
                  st[surroundingFrontLeftAccelDiffMps2] = entry.surroundingFrontLeftAccelDiffMps2
                  st[surroundingFrontLeftTtcSeconds] = entry.surroundingFrontLeftTtcSeconds
                  st[surroundingFrontLeftTgSeconds] = entry.surroundingFrontLeftTgSeconds

                  st[surroundingDistFrontRight] = entry.surroundingDistFrontRight
                  st[surroundingFrontRightSpeedMps] = entry.surroundingFrontRightSpeedMps
                  st[surroundingFrontRightFrontBumperPosMeters] =
                      entry.surroundingFrontRightFrontBumperPosMeters
                  st[surroundingFrontRightBackBumperPosMeters] =
                      entry.surroundingFrontRightBackBumperPosMeters
                  st[surroundingFrontRightAccelMps2] = entry.surroundingFrontRightAccelMps2
                  st[surroundingFrontRightSpeedDiffMps] = entry.surroundingFrontRightSpeedDiffMps
                  st[surroundingFrontRightAccelDiffMps2] = entry.surroundingFrontRightAccelDiffMps2
                  st[surroundingFrontRightTtcSeconds] = entry.surroundingFrontRightTtcSeconds
                  st[surroundingFrontRightTgSeconds] = entry.surroundingFrontRightTgSeconds

                  st[surroundingDistRearLeft] = entry.surroundingDistRearLeft
                  st[surroundingRearLeftSpeedMps] = entry.surroundingRearLeftSpeedMps
                  st[surroundingRearLeftFrontBumperPosMeters] =
                      entry.surroundingRearLeftFrontBumperPosMeters
                  st[surroundingRearLeftBackBumperPosMeters] =
                      entry.surroundingRearLeftBackBumperPosMeters
                  st[surroundingRearLeftAccelMps2] = entry.surroundingRearLeftAccelMps2
                  st[surroundingRearLeftSpeedDiffMps] = entry.surroundingRearLeftSpeedDiffMps
                  st[surroundingRearLeftAccelDiffMps2] = entry.surroundingRearLeftAccelDiffMps2
                  st[surroundingRearLeftTtcSeconds] = entry.surroundingRearLeftTtcSeconds
                  st[surroundingRearLeftTgSeconds] = entry.surroundingRearLeftTgSeconds

                  st[surroundingDistRearRight] = entry.surroundingDistRearRight
                  st[surroundingRearRightSpeedMps] = entry.surroundingRearRightSpeedMps
                  st[surroundingRearRightFrontBumperPosMeters] =
                      entry.surroundingRearRightFrontBumperPosMeters
                  st[surroundingRearRightBackBumperPosMeters] =
                      entry.surroundingRearRightBackBumperPosMeters
                  st[surroundingRearRightAccelMps2] = entry.surroundingRearRightAccelMps2
                  st[surroundingRearRightSpeedDiffMps] = entry.surroundingRearRightSpeedDiffMps
                  st[surroundingRearRightAccelDiffMps2] = entry.surroundingRearRightAccelDiffMps2
                  st[surroundingRearRightTtcSeconds] = entry.surroundingRearRightTtcSeconds
                  st[surroundingRearRightTgSeconds] = entry.surroundingRearRightTgSeconds

                  st[collisionTimeSeconds] = entry.collisionTimeSeconds
                  st[collisionType] = entry.collisionType
                  st[collisionLane] = entry.collisionLane
                  st[collisionPositionOnLaneMeters] = entry.collisionPositionOnLaneMeters
                  st[collisionColliderVehicleId] = entry.collisionColliderVehicleId
                  st[collisionColliderLane] = entry.collisionColliderLane
                  st[collisionColliderSpeedMps] = entry.collisionColliderSpeedMps
                  st[collisionColliderAccelMps2] = entry.collisionColliderAccelMps2
                  st[collisionColliderFrontBumperPosMeters] =
                      entry.collisionColliderFrontBumperPosMeters
                  st[collisionColliderBackBumperPosMeters] =
                      entry.collisionColliderBackBumperPosMeters
                  st[collisionVictimVehicleId] = entry.collisionVictimVehicleId
                  st[collisionVictimLane] = entry.collisionVictimLane
                  st[collisionVictimSpeedMps] = entry.collisionVictimSpeedMps
                  st[collisionVictimAccelMps2] = entry.collisionVictimAccelMps2
                  st[collisionVictimFrontBumperPosMeters] =
                      entry.collisionVictimFrontBumperPosMeters
                  st[collisionVictimBackBumperPosMeters] = entry.collisionVictimBackBumperPosMeters

                  st[allVehiclesJson] = entry.allVehiclesJson

                  st[createdAt] = entry.createdAt
                }
            .single()

    row.toEntry()
  }

  /**
   * Deletes a [MetricFailedMonitorsEntry] by its unique identifier.
   *
   * @param id Unique identifier of the metric entry to delete.
   * @return The number of rows deleted (0 or 1).
   */
  fun deleteById(id: Int): Int = db {
    MetricFailedMonitorsTable.deleteWhere { MetricFailedMonitorsTable.id eq id }
  }

  /**
   * Deletes all [MetricFailedMonitorsEntry] associated with a specific evaluation run.
   *
   * @param runId Unique identifier of the evaluation run.
   * @return The number of rows deleted.
   */
  fun deleteByRun(runId: Int): Int = db {
    MetricFailedMonitorsTable.deleteWhere { MetricFailedMonitorsTable.run eq runId }
  }

  /**
   * Converts a [ResultRow] to a [MetricFailedMonitorsEntry].
   *
   * @return The corresponding [MetricFailedMonitorsEntry].
   */
  private fun ResultRow.toEntry(): MetricFailedMonitorsEntry =
      MetricFailedMonitorsEntry(
          id = this[MetricFailedMonitorsTable.id].value,
          runId = this[MetricFailedMonitorsTable.run].value,
          tscId = this[MetricFailedMonitorsTable.tsc].value,
          mutantId = this[MetricFailedMonitorsTable.mutant].value,
          scenarioConfigId = this[MetricFailedMonitorsTable.startingScenarioConfiguration].value,
          currentTSCInstanceId = this[MetricFailedMonitorsTable.currentTSCInstance].value,
          lastTickTSCInstanceId = this[MetricFailedMonitorsTable.lastTickTSCInstance]?.value,
          previousTSCInstanceId =
              this[MetricFailedMonitorsTable.previouslyChangedTSCInstance]?.value,
          previousTSCInstanceTick =
              this[MetricFailedMonitorsTable.previouslyChangedTSCInstanceTick],
          tick = this[MetricFailedMonitorsTable.tick],
          egoManeuverSpeed = this[MetricFailedMonitorsTable.egoManeuverSpeed],
          egoManeuverLangeChange = this[MetricFailedMonitorsTable.egoManeuverLaneChange],
          egoLane = this[MetricFailedMonitorsTable.egoLane],
          egoSpeedMps = this[MetricFailedMonitorsTable.egoSpeedMps],
          egoAccelMps2 = this[MetricFailedMonitorsTable.egoAccelMps2],
          egoFrontBumperPosMeters = this[MetricFailedMonitorsTable.egoFrontBumperPosMeters],
          egoBackBumperPosMeters = this[MetricFailedMonitorsTable.egoBackBumperPosMeters],
          monitorG0Failed = this[MetricFailedMonitorsTable.monitorG0Failed],
          monitorG1Failed = this[MetricFailedMonitorsTable.monitorG1Failed],
          monitorG2Failed = this[MetricFailedMonitorsTable.monitorG2Failed],
          monitorG3Failed = this[MetricFailedMonitorsTable.monitorG3Failed],
          monitorG4Failed = this[MetricFailedMonitorsTable.monitorG4Failed],
          monitorI1Failed = this[MetricFailedMonitorsTable.monitorI1Failed],
          monitorI2Failed = this[MetricFailedMonitorsTable.monitorI2Failed],
          nextTickMonitorG0Failed = this[MetricFailedMonitorsTable.nextTickMonitorG0Failed],
          nextTickMonitorG1Failed = this[MetricFailedMonitorsTable.nextTickMonitorG1Failed],
          nextTickMonitorG2Failed = this[MetricFailedMonitorsTable.nextTickMonitorG2Failed],
          nextTickMonitorG3Failed = this[MetricFailedMonitorsTable.nextTickMonitorG3Failed],
          nextTickMonitorG4Failed = this[MetricFailedMonitorsTable.nextTickMonitorG4Failed],
          nextTickMonitorI1Failed = this[MetricFailedMonitorsTable.nextTickMonitorI1Failed],
          nextTickMonitorI2Failed = this[MetricFailedMonitorsTable.nextTickMonitorI2Failed],
          surroundingDistFront = this[MetricFailedMonitorsTable.surroundingDistFront],
          surroundingFrontSpeedMps = this[MetricFailedMonitorsTable.surroundingFrontSpeedMps],
          surroundingFrontFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontFrontBumperPosMeters],
          surroundingFrontBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontBackBumperPosMeters],
          surroundingFrontAccelMps2 = this[MetricFailedMonitorsTable.surroundingFrontAccelMps2],
          surroundingFrontSpeedDiffMps =
              this[MetricFailedMonitorsTable.surroundingFrontSpeedDiffMps],
          surroundingFrontAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingFrontAccelDiffMps2],
          surroundingFrontTtcSeconds = this[MetricFailedMonitorsTable.surroundingFrontTtcSeconds],
          surroundingFrontTgSeconds = this[MetricFailedMonitorsTable.surroundingFrontTgSeconds],
          surroundingDistRear = this[MetricFailedMonitorsTable.surroundingDistRear],
          surroundingRearSpeedMps = this[MetricFailedMonitorsTable.surroundingRearSpeedMps],
          surroundingRearFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearFrontBumperPosMeters],
          surroundingRearBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearBackBumperPosMeters],
          surroundingRearAccelMps2 = this[MetricFailedMonitorsTable.surroundingRearAccelMps2],
          surroundingRearSpeedDiffMps = this[MetricFailedMonitorsTable.surroundingRearSpeedDiffMps],
          surroundingRearAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingRearAccelDiffMps2],
          surroundingRearTtcSeconds = this[MetricFailedMonitorsTable.surroundingRearTtcSeconds],
          surroundingRearTgSeconds = this[MetricFailedMonitorsTable.surroundingRearTgSeconds],
          surroundingDistFrontLeft = this[MetricFailedMonitorsTable.surroundingDistFrontLeft],
          surroundingFrontLeftSpeedMps =
              this[MetricFailedMonitorsTable.surroundingFrontLeftSpeedMps],
          surroundingFrontLeftFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontLeftFrontBumperPosMeters],
          surroundingFrontLeftBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontLeftBackBumperPosMeters],
          surroundingFrontLeftAccelMps2 =
              this[MetricFailedMonitorsTable.surroundingFrontLeftAccelMps2],
          surroundingFrontLeftSpeedDiffMps =
              this[MetricFailedMonitorsTable.surroundingFrontLeftSpeedDiffMps],
          surroundingFrontLeftAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingFrontLeftAccelDiffMps2],
          surroundingFrontLeftTtcSeconds =
              this[MetricFailedMonitorsTable.surroundingFrontLeftTtcSeconds],
          surroundingFrontLeftTgSeconds =
              this[MetricFailedMonitorsTable.surroundingFrontLeftTgSeconds],
          surroundingDistFrontRight = this[MetricFailedMonitorsTable.surroundingDistFrontRight],
          surroundingFrontRightSpeedMps =
              this[MetricFailedMonitorsTable.surroundingFrontRightSpeedMps],
          surroundingFrontRightFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontRightFrontBumperPosMeters],
          surroundingFrontRightBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingFrontRightBackBumperPosMeters],
          surroundingFrontRightAccelMps2 =
              this[MetricFailedMonitorsTable.surroundingFrontRightAccelMps2],
          surroundingFrontRightSpeedDiffMps =
              this[MetricFailedMonitorsTable.surroundingFrontRightSpeedDiffMps],
          surroundingFrontRightAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingFrontRightAccelDiffMps2],
          surroundingFrontRightTtcSeconds =
              this[MetricFailedMonitorsTable.surroundingFrontRightTtcSeconds],
          surroundingFrontRightTgSeconds =
              this[MetricFailedMonitorsTable.surroundingFrontRightTgSeconds],
          surroundingDistRearLeft = this[MetricFailedMonitorsTable.surroundingDistRearLeft],
          surroundingRearLeftSpeedMps = this[MetricFailedMonitorsTable.surroundingRearLeftSpeedMps],
          surroundingRearLeftFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearLeftFrontBumperPosMeters],
          surroundingRearLeftBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearLeftBackBumperPosMeters],
          surroundingRearLeftAccelMps2 =
              this[MetricFailedMonitorsTable.surroundingRearLeftAccelMps2],
          surroundingRearLeftSpeedDiffMps =
              this[MetricFailedMonitorsTable.surroundingRearLeftSpeedDiffMps],
          surroundingRearLeftAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingRearLeftAccelDiffMps2],
          surroundingRearLeftTtcSeconds =
              this[MetricFailedMonitorsTable.surroundingRearLeftTtcSeconds],
          surroundingRearLeftTgSeconds =
              this[MetricFailedMonitorsTable.surroundingRearLeftTgSeconds],
          surroundingDistRearRight = this[MetricFailedMonitorsTable.surroundingDistRearRight],
          surroundingRearRightSpeedMps =
              this[MetricFailedMonitorsTable.surroundingRearRightSpeedMps],
          surroundingRearRightFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearRightFrontBumperPosMeters],
          surroundingRearRightBackBumperPosMeters =
              this[MetricFailedMonitorsTable.surroundingRearRightBackBumperPosMeters],
          surroundingRearRightAccelMps2 =
              this[MetricFailedMonitorsTable.surroundingRearRightAccelMps2],
          surroundingRearRightSpeedDiffMps =
              this[MetricFailedMonitorsTable.surroundingRearRightSpeedDiffMps],
          surroundingRearRightAccelDiffMps2 =
              this[MetricFailedMonitorsTable.surroundingRearRightAccelDiffMps2],
          surroundingRearRightTtcSeconds =
              this[MetricFailedMonitorsTable.surroundingRearRightTtcSeconds],
          surroundingRearRightTgSeconds =
              this[MetricFailedMonitorsTable.surroundingRearRightTgSeconds],
          collisionTimeSeconds = this[MetricFailedMonitorsTable.collisionTimeSeconds],
          collisionType = this[MetricFailedMonitorsTable.collisionType],
          collisionLane = this[MetricFailedMonitorsTable.collisionLane],
          collisionPositionOnLaneMeters =
              this[MetricFailedMonitorsTable.collisionPositionOnLaneMeters],
          collisionColliderVehicleId = this[MetricFailedMonitorsTable.collisionColliderVehicleId],
          collisionColliderLane = this[MetricFailedMonitorsTable.collisionColliderLane],
          collisionColliderSpeedMps = this[MetricFailedMonitorsTable.collisionColliderSpeedMps],
          collisionColliderAccelMps2 = this[MetricFailedMonitorsTable.collisionColliderAccelMps2],
          collisionColliderFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.collisionColliderFrontBumperPosMeters],
          collisionColliderBackBumperPosMeters =
              this[MetricFailedMonitorsTable.collisionColliderBackBumperPosMeters],
          collisionVictimVehicleId = this[MetricFailedMonitorsTable.collisionVictimVehicleId],
          collisionVictimLane = this[MetricFailedMonitorsTable.collisionVictimLane],
          collisionVictimSpeedMps = this[MetricFailedMonitorsTable.collisionVictimSpeedMps],
          collisionVictimAccelMps2 = this[MetricFailedMonitorsTable.collisionVictimAccelMps2],
          collisionVictimFrontBumperPosMeters =
              this[MetricFailedMonitorsTable.collisionVictimFrontBumperPosMeters],
          collisionVictimBackBumperPosMeters =
              this[MetricFailedMonitorsTable.collisionVictimBackBumperPosMeters],
          allVehiclesJson = this[MetricFailedMonitorsTable.allVehiclesJson],
          createdAt = this[MetricFailedMonitorsTable.createdAt],
      )
}
