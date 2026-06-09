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

import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.Count
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficLongTailEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficScenariosEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficScenariosTable
import tools.aqua.stars.coverage.significance.db.tables.TSCInstancesTable

/** Repository for [HighwayTrafficScenariosEntry]s. */
object HighwayTrafficScenariosRepository {

  /**
   * Returns a list containing all entries in the table.
   *
   * @return The list containing all entries.
   */
  fun getAll(): List<HighwayTrafficScenariosEntry> = db {
    HighwayTrafficScenariosTable.selectAll().map { it.toEntry() }
  }

  /**
   * Loads the highway traffic long tail entries.
   *
   * @return The list containing all entries.
   */
  fun loadHighwayTrafficLongTailEntries(): List<HighwayTrafficLongTailEntry> = db {
    val countExpr: Count = HighwayTrafficScenariosTable.id.count()

    val list =
        HighwayTrafficScenariosTable.join(
                otherTable = TSCInstancesTable,
                joinType = JoinType.INNER,
                onColumn = HighwayTrafficScenariosTable.tscInstance,
                otherColumn = TSCInstancesTable.id)
            .select(
                HighwayTrafficScenariosTable.tscInstance, TSCInstancesTable.instanceJson, countExpr)
            .groupBy(HighwayTrafficScenariosTable.tscInstance, TSCInstancesTable.instanceJson)
            .map { row ->
              HighwayTrafficLongTailEntry(
                  tscInstanceId = row[HighwayTrafficScenariosTable.tscInstance].value,
                  tscInstanceJson = row[TSCInstancesTable.instanceJson],
                  longTailValue = row[countExpr],
                  createdAt = Instant.now())
            }
            .toMutableList()

    val tscInstances = TSCInstancesRepository.getAll()

    // Add entries with count 0 for tscInstances that are not present in the result
    val missingInstances =
        tscInstances.filterNot { tscInstance ->
          list.any { longTailEntry -> tscInstance.id == longTailEntry.tscInstanceId }
        }

    list.addAll(
        missingInstances.map { tscInstance ->
          HighwayTrafficLongTailEntry(
              tscInstanceId = tscInstance.id!!,
              tscInstanceJson = tscInstance.instanceJson,
              longTailValue = 0,
              createdAt = Instant.now())
        })

    return@db list
  }

  /**
   * Returns a list containing all instance ids in the table.
   *
   * @return The list containing all instance ids.
   */
  fun getInstanceIds(): List<UUID> = db {
    HighwayTrafficScenariosTable.select(HighwayTrafficScenariosTable.tscInstance).map {
      it[HighwayTrafficScenariosTable.tscInstance].value
    }
  }

  /**
   * Inserts a list of entries into the table.
   *
   * @param entries The list of entries to insert.
   */
  fun batchInsert(entries: List<HighwayTrafficScenariosEntry>) = db {
    if (entries.isEmpty()) return@db

    HighwayTrafficScenariosTable.batchInsert(entries) { e ->
      this[HighwayTrafficScenariosTable.seed] = e.seed
      this[HighwayTrafficScenariosTable.crowdiness] = e.crowdiness
      this[HighwayTrafficScenariosTable.vehicleId] = e.vehicleId
      this[HighwayTrafficScenariosTable.vehicleType] = e.vehicleType
      this[HighwayTrafficScenariosTable.tick] = e.tick
      this[HighwayTrafficScenariosTable.lane] = e.lane
      this[HighwayTrafficScenariosTable.speed] = e.speed
      this[HighwayTrafficScenariosTable.position] = e.position
      this[HighwayTrafficScenariosTable.tscInstance] = e.tscInstanceId
    }
  }

  /**
   * Converts a [ResultRow] to a [HighwayTrafficScenariosEntry].
   *
   * @return Converted [HighwayTrafficScenariosEntry].
   */
  private fun ResultRow.toEntry(): HighwayTrafficScenariosEntry =
      HighwayTrafficScenariosEntry(
          id = this[HighwayTrafficScenariosTable.id].value,
          seed = this[HighwayTrafficScenariosTable.seed],
          crowdiness = this[HighwayTrafficScenariosTable.crowdiness],
          vehicleId = this[HighwayTrafficScenariosTable.vehicleId],
          vehicleType = this[HighwayTrafficScenariosTable.vehicleType],
          tick = this[HighwayTrafficScenariosTable.tick],
          lane = this[HighwayTrafficScenariosTable.lane],
          speed = this[HighwayTrafficScenariosTable.speed],
          position = this[HighwayTrafficScenariosTable.position],
          tscInstanceId = this[HighwayTrafficScenariosTable.tscInstance].value,
          createdAt = this[HighwayTrafficScenariosTable.createdAt],
      )

  /** Clears the table. */
  fun clear() = db { HighwayTrafficScenariosTable.deleteAll() }
}
