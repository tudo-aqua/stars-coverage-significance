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

package tools.aqua.stars.coverage.significance.db.dataclasses

import java.time.Instant

/**
 * Data class representing a chunk job for processing a range of sequences for a specific mutant in
 * a test run.
 *
 * @property jobId Unique identifier for the chunk job.
 * @property runId Unique identifier for the test run.
 * @property mutantId Unique identifier for the mutant.
 * @property seqFrom Starting sequence number (inclusive).
 * @property seqTo Ending sequence number (inclusive).
 * @property status Status of the chunk job.
 * @property attempts Number of attempts to execute the chunk job.
 * @property lockedBy Identifier of the worker that currently holds the lock on the chunk job.
 * @property lockedAt Timestamp of when the chunk job was locked.
 * @property startedAt Timestamp of when the chunk job was started.
 * @property finishedAt Timestamp of when the chunk job was finished.
 * @property errorText Error message if the chunk job failed.
 */
data class MutantScenarioChunkJob(
    val jobId: Long? = null,
    val runId: Int,
    val mutantId: Int,
    val seqFrom: Long,
    val seqTo: Long,
    val status: JobStatus = JobStatus.PENDING,
    val attempts: Int = 0,
    val lockedBy: String? = null,
    val lockedAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val errorText: String? = null
)
