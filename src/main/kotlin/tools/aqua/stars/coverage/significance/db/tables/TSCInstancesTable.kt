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

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing TSC instances.
 *
 * @property tsc TSC.
 * @property createdAt Timestamp of the TSC instance.
 * @property instanceHash Hash of the TSC instance.
 * @property instanceJson JSON representation of the TSC instance.
 */
object TSCInstancesTable : UUIDTable("tsc_instances") {
  val tsc =
      reference(
          name = "tsc_id",
          foreign = TSCsTable,
          onDelete = ReferenceOption.CASCADE,
          onUpdate = ReferenceOption.CASCADE)
  val createdAt = timestamp("created_at")
  val instanceHash = varchar("instance_hash", 64)
  val instanceJson = text("instance_json")

  init {
    index(true, instanceJson)
    index(false, tsc)
    index(true, tsc, instanceHash)
  }
}
