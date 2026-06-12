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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import tools.aqua.stars.coverage.significance.db.dataclasses.ChunkJobsProgress
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficAnalysisJob
import tools.aqua.stars.coverage.significance.db.dataclasses.JobStatus
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficAnalysisJobsTable

/** Repository for managing highway traffic analysis jobs. */
object HighwayTrafficAnalysisJobsRepository {

  /** Removes all entries from the database. */
  fun clearTable() = transaction { HighwayTrafficAnalysisJobsTable.deleteAll() }

  /**
   * Inserts a list of chunk jobs into the database.
   *
   * @param chunkJobs List of chunk jobs to insert.
   */
  fun batchInsert(chunkJobs: List<HighwayTrafficAnalysisJob>) = db {
    HighwayTrafficAnalysisJobsTable.batchInsert(chunkJobs) { chunkJob ->
      this[HighwayTrafficAnalysisJobsTable.run] = chunkJob.runId
      this[HighwayTrafficAnalysisJobsTable.seedFromInclusive] = chunkJob.seedFromInclusive
      this[HighwayTrafficAnalysisJobsTable.seedToExclusive] = chunkJob.seedToExclusive
      this[HighwayTrafficAnalysisJobsTable.crowdiness] = chunkJob.crowdiness
      this[HighwayTrafficAnalysisJobsTable.status] = chunkJob.status
      this[HighwayTrafficAnalysisJobsTable.attempts] = chunkJob.attempts
    }
  }

  /**
   * Retrieves the progress of chunk jobs for a given run ID.
   *
   * @param runId The id of the run to get progress for.
   * @return A [ChunkJobsProgress] object containing the progress details.
   */
  fun getProgress(runId: Int): ChunkJobsProgress = db {
    val cnt = HighwayTrafficAnalysisJobsTable.id.count()

    // Returns rows: (status, count)
    val byStatus =
        HighwayTrafficAnalysisJobsTable.select(HighwayTrafficAnalysisJobsTable.status, cnt)
            .where { HighwayTrafficAnalysisJobsTable.run eq runId }
            .groupBy(HighwayTrafficAnalysisJobsTable.status)
            .associate { row -> row[HighwayTrafficAnalysisJobsTable.status] to row[cnt] }

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
   * @return The claimed [HighwayTrafficAnalysisJob] or null if no pending jobs exist.
   */
  fun claimNextJob(runId: Int, workerId: String): HighwayTrafficAnalysisJob? = db {
    val row =
        HighwayTrafficAnalysisJobsTable.select(
                HighwayTrafficAnalysisJobsTable.id,
                HighwayTrafficAnalysisJobsTable.run,
                HighwayTrafficAnalysisJobsTable.seedFromInclusive,
                HighwayTrafficAnalysisJobsTable.seedToExclusive,
                HighwayTrafficAnalysisJobsTable.attempts,
                HighwayTrafficAnalysisJobsTable.crowdiness)
            .where {
              (HighwayTrafficAnalysisJobsTable.run eq runId) and
                  (HighwayTrafficAnalysisJobsTable.status eq JobStatus.PENDING)
            }
            .orderBy(HighwayTrafficAnalysisJobsTable.id to SortOrder.ASC)
            .limit(1)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(
                    ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED, HighwayTrafficAnalysisJobsTable))
            .firstOrNull() ?: return@db null

    val jobId = row[HighwayTrafficAnalysisJobsTable.id].value
    val now = Instant.now()

    HighwayTrafficAnalysisJobsTable.update({ HighwayTrafficAnalysisJobsTable.id eq jobId }) {
      it[status] = JobStatus.RUNNING
      it[attempts] = attempts + 1
      it[lockedBy] = workerId
      it[lockedAt] = now
      it[startedAt] = now
      it[errorText] = null
    }

    HighwayTrafficAnalysisJob(
        jobId = jobId,
        runId = row[HighwayTrafficAnalysisJobsTable.run].value,
        seedFromInclusive = row[HighwayTrafficAnalysisJobsTable.seedFromInclusive],
        seedToExclusive = row[HighwayTrafficAnalysisJobsTable.seedToExclusive],
        crowdiness = row[HighwayTrafficAnalysisJobsTable.crowdiness],
        status = JobStatus.RUNNING,
        attempts = row[HighwayTrafficAnalysisJobsTable.attempts],
    )
  }

  /**
   * Marks the specified chunk job as done.
   *
   * @param jobId The ID of the job to mark as done.
   */
  fun markDone(jobId: Long) = db {
    HighwayTrafficAnalysisJobsTable.update({ HighwayTrafficAnalysisJobsTable.id eq jobId }) {
      it[status] = JobStatus.DONE
      it[finishedAt] = Instant.now()
      it[lockedAt] = null
      it[lockedBy] = null
    }
  }

  /**
   * Marks the specified chunk job as failed or requeues it based on the number of attempts.
   *
   * @param jobId The ID of the job to mark as failed or requeue.
   * @param error The error message associated with the failure.
   * @param maxAttempts The maximum number of attempts before marking as failed.
   */
  fun markFailedOrRequeue(jobId: Long, error: String, maxAttempts: Int) = db {
    val truncated = error.take(10_000)

    // Retry path: only if attempts < maxAttempts
    val retried =
        HighwayTrafficAnalysisJobsTable.update({
          (HighwayTrafficAnalysisJobsTable.id eq jobId) and
              (HighwayTrafficAnalysisJobsTable.attempts less maxAttempts)
        }) {
          it[status] = JobStatus.PENDING
          it[errorText] = truncated
          it[lockedBy] = null
          it[lockedAt] = null
          // Do not set finishedAt on retry
        }

    if (retried == 0) {
      // Final fail path
      HighwayTrafficAnalysisJobsTable.update({ HighwayTrafficAnalysisJobsTable.id eq jobId }) {
        it[status] = JobStatus.FAILED
        it[errorText] = truncated
        it[lockedBy] = null
        it[lockedAt] = null
        it[finishedAt] = Instant.now()
      }
    }
  }
}
