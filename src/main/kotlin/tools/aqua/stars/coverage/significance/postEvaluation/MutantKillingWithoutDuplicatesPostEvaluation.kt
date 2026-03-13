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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.plots.createPlotData
import tools.aqua.stars.coverage.significance.toFileNameSuffix

object MutantKillingWithoutDuplicatesPostEvaluation {

  fun evaluate(
      failedMonitorMapping: List<ScenarioFailure>,
      monitorCombinations: MutableList<Set<MonitorViolation>>
  ) = runBlocking {
    val mutantIdsToEvaluate = filter()
    println("Finished filtering mutants. Start creating plot data...")

    monitorCombinations
        .mapIndexed { index, monitorCombination ->
          async(Dispatchers.Default) {
            println("Evaluating monitor combination: ${monitorCombination.toFileNameSuffix()}")
            createPlotData(
                metricName = "mutant_killing_without_duplicates",
                scenarioFailures = failedMonitorMapping,
                selectedMonitors = monitorCombination,
                baseSeed = 42L + index,
                relevantMutants = mutantIdsToEvaluate)
          }
        }
        .awaitAll()

    return@runBlocking failedMonitorMapping
  }

  fun filter(): List<UUID> {
    println("Start filtering mutants...")
    var listOfFailures: List<MutantFailure> = emptyList()
    var mutants: List<UUID> = emptyList()
    db {
      listOfFailures =
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
      println("Finished loading failures.")

      mutants = MutantsTable.select(MutantsTable.id).map { it[MutantsTable.id].value }
      println("Finished loading mutant UUIDs.")
    }

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

  private fun mutantsIdentical(
      data: List<MutantFailure>,
      mutant1ID: UUID,
      mutant2ID: UUID
  ): Boolean {
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
}
