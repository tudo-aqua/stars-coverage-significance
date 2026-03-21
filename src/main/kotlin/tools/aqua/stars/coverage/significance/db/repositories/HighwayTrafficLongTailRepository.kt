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

import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficLongTailEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficLongTailTable
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficScenariosTable

object HighwayTrafficLongTailRepository {

  fun getAll(): List<HighwayTrafficLongTailEntry> = db {
    HighwayTrafficLongTailTable.selectAll().map { it.toEntry() }
  }

  fun getInstanceIds(): List<UUID> = db {
    HighwayTrafficLongTailTable.select(HighwayTrafficScenariosTable.tscInstance).map {
      it[HighwayTrafficScenariosTable.tscInstance].value
    }
  }

  fun batchInsert(entries: List<HighwayTrafficLongTailEntry>) = db {
    if (entries.isEmpty()) return@db

    HighwayTrafficLongTailTable.batchInsert(entries) { e ->
      this[HighwayTrafficLongTailTable.tscInstance] = e.tscInstanceId
      this[HighwayTrafficLongTailTable.tscInstanceJson] = e.tscInstanceJson
      this[HighwayTrafficLongTailTable.longTail] = e.longTailValue
      this[HighwayTrafficLongTailTable.createdAt] = e.createdAt
    }
  }

  fun ResultRow.toEntry(): HighwayTrafficLongTailEntry =
      HighwayTrafficLongTailEntry(
          id = this[HighwayTrafficLongTailTable.id].value,
          tscInstanceId = this[HighwayTrafficLongTailTable.tscInstance].value,
          tscInstanceJson = this[HighwayTrafficLongTailTable.tscInstanceJson],
          longTailValue = this[HighwayTrafficLongTailTable.longTail],
          createdAt = this[HighwayTrafficLongTailTable.createdAt])
}
