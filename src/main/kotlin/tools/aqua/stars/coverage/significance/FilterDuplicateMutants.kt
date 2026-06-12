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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.DistinctMutantEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DistinctMutantsRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure

/** Filters mutants that are behavioral equivalent and writes the results back to the database. */
fun main() {
  DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))

  val filteredMutantIds = filter(mutantFailuresFromDB, mutantIds)

  db {
    println("Cleaning table")
    DistinctMutantsRepository.cleanTable()
    println("Inserting into Database")
    DistinctMutantsRepository.insertAll(filteredMutantIds.map { DistinctMutantEntry(it) })
    println("Finished!")
  }
}

/**
 * Filters mutants that are behavioral equivalent.
 *
 * @param listOfFailures The mutant failures as they come from the DB.
 * @param mutants The mutants as they come from the DB.
 * @return The mutants that are behavioral distinct.
 */
fun filter(listOfFailures: List<MutantFailure>, mutants: List<Int>): List<Int> {
  println("Start comparing mutants...")
  val behavioralDistinctMutants = mutableListOf<MutableList<Int>>()
  mutants.forEachIndexed { index, mutantId ->
    print("$index: ")
    for (existingMutant in behavioralDistinctMutants) {
      if (mutantsIdentical(listOfFailures, existingMutant.first(), mutantId)) {
        println("Mutant $mutantId is identical to $existingMutant")
        existingMutant.add(mutantId)
        return@forEachIndexed
      }
    }

    println("Mutant $mutantId is new")
    behavioralDistinctMutants.add(mutableListOf(mutantId))
  }

  behavioralDistinctMutants.forEachIndexed { index, ids ->
    println("$index: ${ids.joinToString(",")})")
  }

  println("Saving behavioral distinct mutants to file...")
  val csvPath =
      Path.of(
          POST_EVALUATION_BASE_DIR,
          "MutantKillingWithoutDuplicates",
          "behavioral_distinct_mutants.csv")
  Files.createDirectories(csvPath.parent)
  csvPath.writeText(behavioralDistinctMutants.joinToString("\n") { it.joinToString(",") })

  return behavioralDistinctMutants.map { it.first() }
}

private fun mutantsIdentical(data: List<MutantFailure>, mutant1ID: Int, mutant2ID: Int): Boolean {
  val failuresMutant1 =
      data.filter { it.mutantID == mutant1ID }.sortedBy { it.startingScenarioConfigurationID }
  val failuresMutant2 =
      data.filter { it.mutantID == mutant2ID }.sortedBy { it.startingScenarioConfigurationID }

  check(failuresMutant1.size == failuresMutant2.size)

  for (index in failuresMutant1.indices) {
    if (failuresMutant1[index].monitorBitmask != failuresMutant2[index].monitorBitmask) {
      return false
    }
  }
  return true
}
