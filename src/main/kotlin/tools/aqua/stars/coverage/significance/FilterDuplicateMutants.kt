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
import java.util.UUID
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.dataclasses.DistinctMutantEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DistinctMutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
  var failedMutantsMapping: List<MutantFailure> = emptyList()
  var mutantIds = emptyList<UUID>()
  println("Start loading database")
  db {
    failedMutantsMapping = buildFailedMutantsMapping()
    mutantIds = MutantsRepository.getAllIds()
  }
  println("Finished loading database")

  val filteredMutantIds = filter(failedMutantsMapping, mutantIds)
  //  val filteredMutantIds = failedMutantsMapping.map { it.mutantID }.toSet()

  db {
    println("Cleaning table")
    DistinctMutantsRepository.cleanTable()
    println("Inserting into Database")
    DistinctMutantsRepository.insertAll(filteredMutantIds.map { DistinctMutantEntry(it) })
    println("Finished!")
  }
}

fun filter(listOfFailures: List<MutantFailure>, mutants: List<UUID>): List<UUID> {
  println("Start comparing mutants...")
  val behavioralDistinctMutants = mutableListOf<MutableList<UUID>>()
  mutants.forEachIndexed { index, mutantUuid ->
    print("$index: ")
    for (existingMutant in behavioralDistinctMutants) {
      if (mutantsIdentical(listOfFailures, existingMutant.first(), mutantUuid)) {
        println("Mutant $mutantUuid is identical to $existingMutant")
        existingMutant.add(mutantUuid)
        return@forEachIndexed
      }
    }

    println("Mutant $mutantUuid is new")
    behavioralDistinctMutants.add(mutableListOf(mutantUuid))
  }

  behavioralDistinctMutants.forEachIndexed { index, uUIDS ->
    println("$index: ${uUIDS.joinToString(",")})")
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

private fun mutantsIdentical(data: List<MutantFailure>, mutant1ID: UUID, mutant2ID: UUID): Boolean {
  val failuresMutant1 =
      data.filter { it.mutantID == mutant1ID }.sortedBy { it.startingScenarioConfigurationID }
  val failuresMutant2 =
      data.filter { it.mutantID == mutant2ID }.sortedBy { it.startingScenarioConfigurationID }

  for (index in failuresMutant1.indices) {
    if (failuresMutant1[index].monitorBitmask != failuresMutant2[index].monitorBitmask) {
      return false
    }
  }
  return true
}

private fun buildFailedMutantsMapping(): List<MutantFailure> =
    MetricFailedMonitorsTable.select(
            MetricFailedMonitorsTable.startingScenarioConfiguration,
            MetricFailedMonitorsTable.mutant,
            MetricFailedMonitorsTable.monitorG0Failed,
            MetricFailedMonitorsTable.monitorG1Failed,
            MetricFailedMonitorsTable.monitorG2Failed,
            MetricFailedMonitorsTable.monitorG4Failed,
            MetricFailedMonitorsTable.monitorI2Failed,
        )
        .map {
          MutantFailure(
              startingScenarioConfigurationID =
                  it[MetricFailedMonitorsTable.startingScenarioConfiguration].value,
              mutantID = it[MetricFailedMonitorsTable.mutant].value,
              monitorBitmask =
                  (if (it[MetricFailedMonitorsTable.monitorG0Failed]) 16 else 0) +
                      (if (it[MetricFailedMonitorsTable.monitorG1Failed]) 8 else 0) +
                      (if (it[MetricFailedMonitorsTable.monitorG2Failed]) 4 else 0) +
                      (if (it[MetricFailedMonitorsTable.monitorG4Failed]) 2 else 0) +
                      (if (it[MetricFailedMonitorsTable.monitorI2Failed]) 1 else 0))
        }
        .toList()
