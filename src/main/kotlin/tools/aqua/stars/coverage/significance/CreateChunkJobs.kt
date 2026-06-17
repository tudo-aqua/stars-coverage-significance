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
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.seed.ChunkJobSeeder

/**
 * This utility creates chunk jobs for all mutants and scenarios and inserts them into the database.
 * It can be run multiple times without creating duplicate jobs, as it first clears the chunk jobs
 * table for convenience.
 */
fun main() {
  DbBootstrap.connectAndCreateSchema()
  val evaluationRunId = EvaluationRunsRepository.insertAndGetId(EvaluationRunEntry())
  println("Created evaluation run: $evaluationRunId")

  // Clear Table for convenience
  MutantScenarioChunkJobsRepository.clearTable()

  val mutantIds = MutantsRepository.getAllIds()

  println("Seeding chunk jobs...")
  val numberOfScenarios = ScenarioStartingConfigurationRepository.getMaxSequenceNumber()
  ChunkJobSeeder.seedChunks(
      runId = evaluationRunId,
      mutantIds = mutantIds,
      chunkSize = 200L,
      scenarioCount = numberOfScenarios)
}
