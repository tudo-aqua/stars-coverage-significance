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
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.seed.MutantGenerator
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.seedGridTrafficScenarios
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.coverage.significance.workers.startStartingValidTSCInstancesWorkerProcess

/** Seed and precompute necessary data for coverage significance evaluation. */
fun main() {
  DbBootstrap.connectAndCreateSchema()

  // Add TSC to db
  TSCsRepository.upsertAndGetId(entry = staticTsc().toTSCEntry())

  // Seed scenarios
  seedGridTrafficScenarios(
      seed = SEED, insertIntoDatabase = true, enablePositionVariance = true, n = 10_000)

  // Seed mutants
  MutantGenerator.seed()

  // Precompute scenario-only metric once
  runStartingValidTSCInstancesEvaluation(parallelism = parallelism - 2)
}

/**
 * Runs the evaluation of starting valid TSC instances in parallel.
 *
 * @param parallelism Number of parallel workers to use.
 */
private fun runStartingValidTSCInstancesEvaluation(parallelism: Int) {
  val maxSeq = ScenarioStartingConfigurationRepository.getMaxSequenceNumber()
  if (maxSeq <= 0L) {
    println("No scenarios found; skipping premetric phase.")
    return
  }

  val existingStartingValidTSCInstances: Long = MetricStartingValidTSCInstancesRepository.count()

  if (existingStartingValidTSCInstances == maxSeq) {
    println("All starting valid TSC instances already exist; skipping calculation.")
    return
  }

  MetricStartingValidTSCInstancesRepository.clearTable()

  val workerCount = minOf(parallelism.coerceAtLeast(1), maxSeq.toInt())
  val base = maxSeq / workerCount
  val rem = maxSeq % workerCount

  var start = 1L
  val processes =
      (0 until workerCount).map { i ->
        val size = base + if (i < rem) 1 else 0 // first 'rem' workers get one extra
        val from = start
        val to = start + size - 1
        start = to + 1

        val name = "ValidStartingTSCInstancesWorker-$i"
        NamedProcess(
            name = name,
            process =
                startStartingValidTSCInstancesWorkerProcess(
                    workerId = name, seqFrom = from, seqTo = to))
      }
  try {
    ProcessGroupRunner.awaitAll(
        groupLabel = "ValidStartingTSCInstancesWorker", processes = processes)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    processes.toList().forEach { it.killProcessTree() }
    throw e
  }
}
