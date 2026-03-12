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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.EvaluationRunEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.HighwayTrafficAnalysisJob
import tools.aqua.stars.coverage.significance.db.dataclasses.JobStatus
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficAnalysisJobsRepository

fun main() {
  DbBootstrap.connectAndCreateSchema()

  val evaluationRunId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created highway traffic evaluation run: $evaluationRunId")

  HighwayTrafficAnalysisJobsRepository.clearTable()

  val crowdinessRange = 4..40
  val repetitions = 2000
  val seedChunkSize = 500

  val jobs =
      crowdinessRange.flatMap { crowdinessIndex ->
        val crowdiness = crowdinessIndex * 100

        (0 until repetitions step seedChunkSize).map { seedFrom ->
          val seedToExclusive = minOf(seedFrom + seedChunkSize, repetitions)

          HighwayTrafficAnalysisJob(
              jobId = 0L,
              runId = evaluationRunId,
              seedFromInclusive = seedFrom,
              seedToExclusive = seedToExclusive,
              crowdiness = crowdiness,
              status = JobStatus.PENDING,
              attempts = 0,
          )
        }
      }

  HighwayTrafficAnalysisJobsRepository.batchInsert(jobs)
  println("Created ${jobs.size} highway traffic analysis chunk jobs")
}
