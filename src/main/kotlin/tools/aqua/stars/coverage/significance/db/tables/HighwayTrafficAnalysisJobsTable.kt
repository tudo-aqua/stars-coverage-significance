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

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp
import tools.aqua.stars.coverage.significance.db.dataclasses.JobStatus

object HighwayTrafficAnalysisJobsTable : LongIdTable("highway_traffic_analysis_chunk_jobs") {
  val run = reference("run_id", EvaluationRunsTable, onDelete = ReferenceOption.CASCADE)

  val seedFromInclusive = integer("seed_from_inclusive")
  val seedToExclusive = integer("seed_to_exclusive")
  val crowdiness = integer("crowdiness")

  val status = enumerationByName("status", 16, JobStatus::class).index()
  val attempts = integer("attempts").default(0)

  val lockedBy = varchar("locked_by", 128).nullable()
  val lockedAt = timestamp("locked_at").nullable()
  val startedAt = timestamp("started_at").nullable()
  val finishedAt = timestamp("finished_at").nullable()

  val errorText = text("error_text").nullable()

  init {
    uniqueIndex(run, seedFromInclusive, seedToExclusive, crowdiness)
    index(false, status, id)
    index(false, status, lockedAt)
    index(false, run)
  }
}
