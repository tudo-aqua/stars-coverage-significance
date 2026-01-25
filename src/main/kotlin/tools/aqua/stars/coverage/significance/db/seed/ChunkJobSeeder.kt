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

package tools.aqua.stars.coverage.significance.db.seed

import java.util.UUID
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantScenarioChunkJob
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable

/** Seeds chunk jobs into the database. */
object ChunkJobSeeder {

  /**
   * Creates PENDING jobs for every mutant across the full scenario range.
   *
   * @param runId Evaluation run ID (single global run).
   * @param mutantIds Mutant IDs to evaluate.
   * @param chunkSize Number of scenarios per chunk (e.g., 500, 1000, 2000).
   * @param scenarioCount Total number of scenarios available.
   */
  fun seedChunks(runId: UUID, mutantIds: List<UUID>, chunkSize: Long, scenarioCount: Int) =
      transaction {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(mutantIds.isNotEmpty()) { "mutantIds must not be empty" }

        // Find max sequence number
        var maxSeq =
            ScenarioStartingConfigurationTable.select(
                    ScenarioStartingConfigurationTable.sequenceNumber.max())
                .firstOrNull()
                ?.get(ScenarioStartingConfigurationTable.sequenceNumber.max()) ?: 0L

        if (maxSeq <= 0L) {
          // Nothing to seed (no scenarios in DB)
          return@transaction
        }

        maxSeq = minOf(maxSeq, scenarioCount.toLong())

        val ranges = buildList {
          var from = 1L
          while (from <= maxSeq) {
            val to = minOf(from + chunkSize - 1, maxSeq)
            add(from to to)
            from = to + 1
          }
        }

        println("Split scenarios into ${ranges.size} chunks.")

        val mutantScenarioChunkJobs = mutableListOf<MutantScenarioChunkJob>()

        mutantIds.forEach { mutantId ->
          ranges.forEach { (from, to) ->
            mutantScenarioChunkJobs.add(
                MutantScenarioChunkJob(
                    runId = runId, mutantId = mutantId, seqFrom = from, seqTo = to))
          }
        }

        println("Write ${mutantScenarioChunkJobs.size} chunk jobs to DB.")
        MutantScenarioChunkJobsRepository.batchInsert(mutantScenarioChunkJobs)
        println("Finished writing chunk jobs to DB.")
      }
}
