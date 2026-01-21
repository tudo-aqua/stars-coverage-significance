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

/**
 * Table storing jobs for evaluating chunks of scenarios for a specific mutant in a specific
 * evaluation run.
 *
 * @property run Evaluation run.
 * @property mutant Mutant.
 * @property seqFrom Sequence number of the first chunk to evaluate.
 * @property seqTo Sequence number of the last chunk to evaluate.
 * @property status Status of the job.
 * @property attempts Number of attempts to execute the job.
 * @property lockedBy Identifier of the worker that currently holds the lock on the job.
 * @property lockedAt Timestamp of when the job was locked.
 * @property startedAt Timestamp of when the job was started.
 * @property finishedAt Timestamp of when the job finished.
 * @property errorText Error message if the job failed.
 */
object MutantScenarioChunkJobsTable : LongIdTable("mutant_scenario_chunk_jobs") {
  val run = reference("run_id", EvaluationRunsTable, onDelete = ReferenceOption.CASCADE)
  val mutant = reference("mutant_id", MutantsTable, onDelete = ReferenceOption.CASCADE)

  val seqFrom = long("seq_from")
  val seqTo = long("seq_to")

  val status = enumerationByName("status", 16, JobStatus::class).index()
  val attempts = integer("attempts").default(0)

  val lockedBy = varchar("locked_by", 128).nullable()
  val lockedAt = timestamp("locked_at").nullable()
  val startedAt = timestamp("started_at").nullable()
  val finishedAt = timestamp("finished_at").nullable()

  val errorText = text("error_text").nullable()

  init {
    uniqueIndex(run, mutant, seqFrom, seqTo)
    index(false, status, id)
    index(false, status, lockedAt)
    index(false, run)
    index(false, mutant)
  }
}
