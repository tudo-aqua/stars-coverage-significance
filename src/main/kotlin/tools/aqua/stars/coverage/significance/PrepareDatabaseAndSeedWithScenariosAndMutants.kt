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

import java.util.UUID
import kotlin.collections.map
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCInstanceEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.db.seed.MutantGenerator
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.seedGridTrafficScenarios
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.ConsoleProgress
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.coverage.significance.utils.toTSCEntry
import tools.aqua.stars.coverage.significance.workers.startStartingValidTSCInstancesWorkerProcess
import tools.aqua.stars.sumo.mutants.AutopilotMutants

val tscListToUseInProject = listOf(tsc())

/** Seed and precompute necessary data for coverage significance evaluation. */
fun main() {
  DbBootstrap.connectAndCreateSchema()

  // Add TSC to db
  tscListToUseInProject.forEach { tsc ->
    val tscId = TSCsRepository.upsertAndGetId(entry = tsc.toTSCEntry())
    insertAllTSCInstance(tsc, tscId)
  }

  // Seed scenarios
  seedGridTrafficScenarios(seed = SEED, insertIntoDatabase = true)
  //  seedGridTrafficScenarios(
  //      seed = SEED,
  //      insertIntoDatabase = true,
  //      onlyInsertFromListOfReadableIds =
  //          listOf(
  //
  // "[0][0]C@50__[0][1]S@50__[0][2]N@50__[1][0]N@110__[1][1]E@110__[1][2]C@110__[2][0]S@170__[2][1]C@170__[2][2]S@170"))

  // Seed mutants
  seedMutants(seedBaseLine = false, seedMutants = true)

  // Precompute scenario-only metric once
  //  runStartingValidTSCInstancesEvaluation(parallelism = parallelism - 2, tscId = tscId)
}

/** Seed all mutants and the baseline mutant into the database. */
private fun seedMutants(
    seedBaseLine: Boolean = true,
    seedMutants: Boolean = true,
    numberOfMutants: Int? = null
): List<UUID> {
  println("Seeding mutants into the database...")
  val existing = MutantsRepository.getAllIds()
  // When all "MutantGenerator.expectedMutantCount " mutants exist and the number of mutants to seed
  // is not specified, return early.
  val expectedMutantCount = AutopilotMutants.byIndex.size
  if (numberOfMutants == null && existing.isNotEmpty() && existing.size == expectedMutantCount) {
    println("Database already seeded with $expectedMutantCount mutants.")
    return existing
  }

  println(
      "Expected mutant count: $expectedMutantCount. Existing mutants in database: ${existing.size}. Seeding mutants...")

  // A new number of mutants was specified. Therefore, clean previously generated mutants.
  MutantsRepository.cleanTable()

  val mutantIds = mutableListOf<UUID>()

  // Seed mutants
  if (seedBaseLine) {
    mutantIds += MutantGenerator.seedBaseline()
  }
  if (seedMutants) {
    mutantIds += MutantGenerator.seed()
    //    mutantIds += MutantGenerator.seed(onlyInsertMutantsWithMutantNumber = listOf(19))
  }

  println("Finished seeding mutants. Total mutants in database: ${mutantIds.size}")
  return mutantIds
}

/**
 * Inserts all TSC instances into the database if they are not already present.
 *
 * @param tsc The TSC whose instances are to be inserted.
 * @param tscId The database ID of the TSC.
 */
private fun insertAllTSCInstance(tsc: TSC<*, *, *, *>, tscId: UUID) = db {
  println("Checking existing TSCInstances in Db...")
  val existingEntries = TSCInstancesRepository.countByTSC(tscId)
  if (existingEntries == tsc.instanceCount.toLong()) {
    println("TSCInstances are already in Db.")
    return@db
  }
  println("TSCInstances not found in Db. Inserting...")
  val consoleProgress = ConsoleProgress(tsc.instanceCount.toInt())
  println("Inserting ${tsc.instanceCount} TSCInstances into Db.")
  tsc.possibleTSCInstances.forEach { tscInstance ->
    consoleProgress.step()
    TSCInstancesRepository.insertIfAbsentReturnId(
        TSCInstanceEntry(tscId = tscId, instanceJson = tscInstance.getJsonString()))
  }
  println("Finished inserting TSCInstances.")
}

/**
 * Runs the evaluation of starting valid TSC instances in parallel.
 *
 * @param parallelism Number of parallel workers to use.
 * @param tscId The database ID of the TSC for which to evaluate starting valid TSC instances.
 */
private fun runStartingValidTSCInstancesEvaluation(parallelism: Int, tscId: UUID) {
  println("Precomputing starting valid TSC instances...")
  val maxSeq = ScenarioStartingConfigurationRepository.getMaxSequenceNumber()
  if (maxSeq <= 0L) {
    println("No scenarios found; skipping premetric phase.")
    return
  }

  val existingStartingValidTSCInstances: Long =
      MetricStartingValidTSCInstancesRepository.countByTSC(tscId)

  if (existingStartingValidTSCInstances == maxSeq) {
    println(
        "All starting valid TSC instances for TSC ${tscId }} already exist; skipping calculation.")
    return
  }

  MetricStartingValidTSCInstancesRepository.clearTSCEntries(tscId)

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
  println("Precomputation finished.")
}
