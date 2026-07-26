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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import tools.aqua.stars.coverage.significance.db.dataclasses.ChunkJobsProgress
import tools.aqua.stars.coverage.significance.db.dataclasses.JobStatus
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantScenarioChunkJob
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable.attempts
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable.mutant
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable.seqFrom
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable.seqTo
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable.status

/** Repository for managing chunk jobs related to mutant scenario runs. */
object MutantScenarioChunkJobsRepository {

  /** Removes all entries from the database. */
  fun clearTable() = transaction { MutantScenarioChunkJobsTable.deleteAll() }

  /**
   * Inserts a list of chunk jobs into the database.
   *
   * @param mutantScenarioChunkJobs List of chunk jobs to insert.
   */
  fun batchInsert(mutantScenarioChunkJobs: List<MutantScenarioChunkJob>) = transaction {
    MutantScenarioChunkJobsTable.batchInsert(mutantScenarioChunkJobs) { chunkJob ->
      this[MutantScenarioChunkJobsTable.run] = chunkJob.runId
      this[mutant] = chunkJob.mutantId
      this[seqFrom] = chunkJob.seqFrom
      this[seqTo] = chunkJob.seqTo
      this[status] = chunkJob.status
      this[attempts] = chunkJob.attempts
    }
  }

  /**
   * Retrieves the progress of chunk jobs for a given run ID.
   *
   * @param runId The id of the run to get progress for.
   * @return A [ChunkJobsProgress] object containing the progress details.
   */
  fun getProgress(runId: Int): ChunkJobsProgress = transaction {
    val cnt = MutantScenarioChunkJobsTable.id.count()

    // Returns rows: (status, count)
    val byStatus =
        MutantScenarioChunkJobsTable.select(status, cnt)
            .where { MutantScenarioChunkJobsTable.run eq runId }
            .groupBy(status)
            .associate { row -> row[status] to row[cnt] }

    val pending = byStatus[JobStatus.PENDING] ?: 0L
    val running = byStatus[JobStatus.RUNNING] ?: 0L
    val done = byStatus[JobStatus.DONE] ?: 0L
    val failed = byStatus[JobStatus.FAILED] ?: 0L
    val total = pending + running + done + failed

    ChunkJobsProgress(
        total = total,
        pending = pending,
        running = running,
        done = done,
        failed = failed,
    )
  }

  /**
   * Claims the next available chunk job for processing.
   *
   * @param runId The id of the run to claim a job from.
   * @param workerId The identifier of the worker claiming the job.
   * @return The claimed [MutantScenarioChunkJob] or null if no pending jobs
   */
  fun claimNextChunkJob(runId: Int, workerId: String): MutantScenarioChunkJob? = transaction {
    val row =
        MutantScenarioChunkJobsTable.select(
                MutantScenarioChunkJobsTable.id,
                MutantScenarioChunkJobsTable.run,
                mutant,
                seqFrom,
                seqTo)
            .where { (MutantScenarioChunkJobsTable.run eq runId) and (status eq JobStatus.PENDING) }
            .orderBy(MutantScenarioChunkJobsTable.id to SortOrder.ASC)
            .limit(1)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(
                    ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED, MutantScenarioChunkJobsTable))
            .firstOrNull() ?: return@transaction null

    val jobId = row[MutantScenarioChunkJobsTable.id].value
    val now = Instant.now()

    MutantScenarioChunkJobsTable.update({ MutantScenarioChunkJobsTable.id eq jobId }) {
      it[status] = JobStatus.RUNNING
      it[attempts] = attempts + 1
      it[lockedBy] = workerId
      it[lockedAt] = now
      it[startedAt] = now
      // errorText is intentionally left untouched here: markFailedOrRequeue appends to it, so
      // clearing it on claim would erase every earlier attempt's error right before the next one
      // could be appended.
    }

    MutantScenarioChunkJob(
        jobId = jobId,
        runId = row[MutantScenarioChunkJobsTable.run].value,
        mutantId = row[mutant].value,
        seqFrom = row[seqFrom],
        seqTo = row[seqTo],
    )
  }

  /**
   * Marks the specified chunk job as done.
   *
   * @param jobId The ID of the job to mark as done.
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
   * The error is appended to any existing `errorText` (prefixed with the attempt number) rather
   * than replacing it, so the failure history of every attempt survives across retries instead of
   * only the most recent one being visible.
   *
   * @param jobId The ID of the job to mark as failed or requeue.
   * @param error The error message associated with the failure.
   * @param maxAttempts The maximum number of attempts before marking as failed.
   */
  fun markFailedOrRequeue(jobId: Long, error: String, maxAttempts: Int) = transaction {
    val truncated = error.take(10_000)

    val row =
        MutantScenarioChunkJobsTable.select(attempts, MutantScenarioChunkJobsTable.errorText)
            .where { MutantScenarioChunkJobsTable.id eq jobId }
            .forUpdate()
            .singleOrNull()
    val currentAttempt = row?.get(attempts) ?: 0
    val previousErrorText = row?.get(MutantScenarioChunkJobsTable.errorText)

    val appendedErrorText = buildString {
      if (previousErrorText != null) {
        append(previousErrorText)
        append("\n\n")
      }
      append("--- Attempt $currentAttempt ---\n")
      append(truncated)
    }

    // Retry path: only if attempts < maxAttempts
    val retried =
        MutantScenarioChunkJobsTable.update({
          (MutantScenarioChunkJobsTable.id eq jobId) and (attempts less maxAttempts)
        }) {
          it[status] = JobStatus.PENDING
          it[errorText] = appendedErrorText
          it[lockedBy] = null
          it[lockedAt] = null
          // Do not set finishedAt on retry
        }

    if (retried == 0) {
      // Final fail path
      MutantScenarioChunkJobsTable.update({ MutantScenarioChunkJobsTable.id eq jobId }) {
        it[status] = JobStatus.FAILED
        it[errorText] = appendedErrorText
        it[lockedBy] = null
        it[lockedAt] = null
        it[finishedAt] = Instant.now()
      }
    }
  }
}
