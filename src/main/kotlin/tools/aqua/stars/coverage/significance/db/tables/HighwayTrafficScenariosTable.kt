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

import java.time.Instant
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object HighwayTrafficScenariosTable : UUIDTable("highway_traffic_scenarios") {
  val seed = integer("seed")
  val crowdiness = integer("crowdiness")
  val vehicleId = varchar("vehicle_id", 1024)
  val vehicleType = varchar("vehicle_type", 1024)
  val tick = long("tick")
  val lane = integer("lane")
  val position = double("position")
  val speed = double("speed")
  val tscInstance =
      reference(
          "tsc_instance_id",
          TSCInstancesTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val createdAt = timestamp("created_at").default(Instant.now())

  init {
    index(true, seed, crowdiness, vehicleId, vehicleType, tick, tscInstance)

    index(false, tscInstance)
    index(false, vehicleId)
  }
}
