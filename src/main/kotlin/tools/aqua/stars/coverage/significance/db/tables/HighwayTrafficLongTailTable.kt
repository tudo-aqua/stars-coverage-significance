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
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing long tail values for highway traffic scenarios.
 *
 * @property tscInstance TSC instance.
 * @property tscInstanceJson JSON representation of the TSC instance.
 * @property longTail Long tail value.
 * @property createdAt Timestamp of when the long tail value was created.
 */
object HighwayTrafficLongTailTable : IntIdTable("highway_traffic_long_tail") {
  val tscInstance =
      reference(
          "tsc_instance_id",
          TSCInstancesTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val tscInstanceJson = text("tsc_instance_json")
  val longTail = long("long_tail_value")
  val createdAt = timestamp("created_at")
}
