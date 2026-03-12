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

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficScenariosEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficScenariosTable

object HighwayTrafficScenariosRepository {

  fun getAll(): List<HighwayTrafficScenariosEntry> = db {
    HighwayTrafficScenariosTable.selectAll().map { it.toEntry() }
  }

  fun batchInsert(entries: List<HighwayTrafficScenariosEntry>) = db {
    if (entries.isEmpty()) return@db

    HighwayTrafficScenariosTable.batchInsert(entries) { e ->
      this[HighwayTrafficScenariosTable.seed] = e.seed
      this[HighwayTrafficScenariosTable.crowdiness] = e.crowdiness
      this[HighwayTrafficScenariosTable.vehicleId] = e.vehicleId
      this[HighwayTrafficScenariosTable.vehicleType] = e.vehicleType
      this[HighwayTrafficScenariosTable.lane] = e.lane
      this[HighwayTrafficScenariosTable.speed] = e.speed
      this[HighwayTrafficScenariosTable.position] = e.position
      this[HighwayTrafficScenariosTable.tscInstance] = e.tscInstanceId
    }
  }

  private fun ResultRow.toEntry(): HighwayTrafficScenariosEntry =
      HighwayTrafficScenariosEntry(
          id = this[HighwayTrafficScenariosTable.id].value,
          seed = this[HighwayTrafficScenariosTable.seed],
          crowdiness = this[HighwayTrafficScenariosTable.crowdiness],
          vehicleId = this[HighwayTrafficScenariosTable.vehicleId],
          vehicleType = this[HighwayTrafficScenariosTable.vehicleType],
          lane = this[HighwayTrafficScenariosTable.lane],
          speed = this[HighwayTrafficScenariosTable.speed],
          position = this[HighwayTrafficScenariosTable.position],
          tscInstanceId = this[HighwayTrafficScenariosTable.tscInstance].value,
          createdAt = this[HighwayTrafficScenariosTable.createdAt],
      )
}
