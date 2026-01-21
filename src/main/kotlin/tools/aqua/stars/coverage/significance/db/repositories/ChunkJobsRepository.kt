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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import tools.aqua.stars.coverage.significance.db.dataclasses.ChunkJob
import tools.aqua.stars.coverage.significance.db.dataclasses.JobStatus
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable

/** Repository for managing chunk jobs related to mutant scenario runs. */
object ChunkJobsRepository {

  /**
   * Claims the next available chunk job for processing by a worker.
   *
   * @param runId The ID of the mutant scenario run.
   * @param workerId The ID of the worker claiming the job.
   * @return The claimed [ChunkJob] or null if no pending jobs are available.
   */
  fun claimNextChunkJob(runId: UUID, workerId: String): ChunkJob? = transaction {
    // Pick one PENDING row, lock it with SKIP LOCKED
    val row =
        MutantScenarioChunkJobsTable.selectAll()
            .where {
              (MutantScenarioChunkJobsTable.run eq runId) and
                  (MutantScenarioChunkJobsTable.status eq JobStatus.PENDING)
            }
            .orderBy(MutantScenarioChunkJobsTable.id to SortOrder.ASC)
            .limit(1)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(
                    ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED, MutantScenarioChunkJobsTable))
            .firstOrNull() ?: return@transaction null

    val jobId = row[MutantScenarioChunkJobsTable.id].value
    val now = Instant.now()

    // Mark RUNNING
    MutantScenarioChunkJobsTable.update({ MutantScenarioChunkJobsTable.id eq jobId }) {
      it[status] = JobStatus.RUNNING
      it[attempts] = row[MutantScenarioChunkJobsTable.attempts] + 1
      it[lockedBy] = workerId
      it[lockedAt] = now
      it[startedAt] = now
      it[errorText] = null
    }

    ChunkJob(
        jobId = jobId,
        runId = row[MutantScenarioChunkJobsTable.run].value,
        mutantId = row[MutantScenarioChunkJobsTable.mutant].value,
        seqFrom = row[MutantScenarioChunkJobsTable.seqFrom],
        seqTo = row[MutantScenarioChunkJobsTable.seqTo])
  }

  /**
   * Marks the specified chunk job as done.
   *
   * @param jobId The ID of the chunk job to mark as done.
   */
  fun markDone(jobId: Long) = transaction {
    MutantScenarioChunkJobsTable.update({ MutantScenarioChunkJobsTable.id eq jobId }) {
      it[status] = JobStatus.DONE
      it[finishedAt] = Instant.now()
      it[lockedAt] = null
      it[lockedBy] = null
    }
  }

  /**
   * Marks the specified chunk job as failed or requeues it based on the number of attempts.
   *
   * @param jobId The ID of the chunk job to mark as failed or requeue.
   * @param error The error message associated with the failure.
   * @param maxAttempts The maximum number of attempts allowed before marking as failed.
   */
  fun markFailedOrRequeue(jobId: Long, error: String, maxAttempts: Int) = transaction {
    val attempts =
        MutantScenarioChunkJobsTable.select(MutantScenarioChunkJobsTable.attempts)
            .where { MutantScenarioChunkJobsTable.id eq jobId }
            .first()[MutantScenarioChunkJobsTable.attempts]

    val retry = attempts < maxAttempts

    MutantScenarioChunkJobsTable.update({ MutantScenarioChunkJobsTable.id eq jobId }) {
      it[status] = if (retry) JobStatus.PENDING else JobStatus.FAILED
      it[errorText] = error.take(10_000)
      it[lockedBy] = null
      it[lockedAt] = null
      if (!retry) it[finishedAt] = Instant.now()
    }
  }
}
