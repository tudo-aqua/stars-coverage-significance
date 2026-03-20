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
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DistinctMutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.postEvaluation.HighwayTrafficAnalysis
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.HighwayTrafficScenarioInstanceId
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures

val failedMonitorMapping: List<ScenarioFailure> by lazy {
  db { buildFailedMonitorMapping(buildFailedMonitorMappingQuery()) }
}
val mutantFailuresMapping: List<MutantFailure> by lazy {
  db { buildFailedMutantsMapping() }
}
val failedMutantsMapping: List<MutantFailure> by lazy {
  failedMutantsMapping.filter { it.monitorBitmask > 0 }
}
val filteredMutantFailures: List<MutantFailure> by lazy {
  db { failedMutantsMapping.filter { it.mutantID in distinctMutantIds } }
}
val monitorCombinations: List<Set<MonitorViolation>> by lazy { db { buildMonitorCombinations() } }
val mutantIds: List<UUID> by lazy { db { MutantsRepository.getAllIds() } }
val distinctMutantIds: List<UUID> by lazy { db { DistinctMutantsRepository.getAllIds() } }
val allScenarioInstances: List<ScenarioIdAndJSON> by lazy {
  db { TSCInstancesRepository.getAllScenariosWithJSON() }
}
val randomTrafficAnalysis: List<HighwayTrafficScenarioInstanceId> by lazy {
  db { HighwayTrafficScenariosRepository.getInstanceIds() }
}
val scenarioIds: List<UUID> by lazy {
  db { TSCInstancesRepository.getAllScenariosWithJSON().map { it.scenarioId } }
}
val longtailDistribution by lazy {
  allScenarioInstances
      .map { it to randomTrafficAnalysis.count { t -> t == it.scenarioId } }
      .sortedByDescending { it.second }
}

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
  //  CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.evaluate()
  //  CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.evaluate()
  //  TotalNumberOfFailedMonitorsPerMonitorPostEvaluation.evaluate()
  //  TotalNumberOfFailedMonitorsPerScenarioPostEvaluation.evaluate()
  //  TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.evaluate()

  //    val countOfScenariosKillingAMutant =
  //        calculateCountOfScenariosKillingMutant(
  //            filteredFailedMutantsMapping = filteredMutantFailures,
  //            distinctMutantIds = distinctMutantIds)

  //    val countOfScenariosKillingAMutantThatIsNotKilledByAllScenarios =
  //        countOfScenariosKillingAMutant.filter { it.second < 160 } // 160 = #TSC instances

  HighwayTrafficAnalysis.evaluate(longtailDistribution)

  //  AccidentsKillingPerScenarioPostEvaluation.evaluate(longtailDistribution,
  // filteredMutantFailures)
  //  CountOfMutantsKilledPerMonitor.evaluate(filteredMutantFailures)

  //    ScenarioByScenarioCrossTable.evaluate(
  //        filteredMutantFailures = filteredMutantFailures, scenarioIds = scenarioIds)

  //    // Plot with long-tail and scatter-plot of two mutants corridors
  //    CountOfMutantsKilledPerScenarioPostEvaluation.evaluate(
  //        allTSCInstances = allScenarioInstances,
  //        randomTrafficTSCInstances = randomTrafficAnalysis,
  //        filteredMutantFailures = filteredMutantFailures)
  //    // Plot with long-tail and scatter-plot of two mutants corridors (Four monitors separately
  // //colored)
  //  CountOfMutantsKilledPerScenarioSplitByMonitorCausingFailurePostEvaluation.evaluate(
  //      allTSCInstances = allScenarioInstances,
  //      randomTrafficTSCInstances = randomTrafficAnalysis,
  //      filteredMutantFailures = filteredMutantFailures)

  // Plot with long-tail and scatter-plot of two mutants corridors (All monitors separately colored)
  //
  // CountOfMutantsKilledPerScenarioSplitByMonitorCausingFailureWithAllMonitorsPostEvaluation.evaluate(
  //      allTSCInstances = allScenarioInstances,
  //      randomTrafficTSCInstances = randomTrafficAnalysis,
  //      filteredMutantFailures = filteredMutantFailures)
  //
  //  // Plots where the different TSC features are located in the scatter-plot
  //  DistributionOfFeaturesInLongtailPostEvaluation.evaluate(
  //      allTSCInstances = allScenarioInstances,
  //      randomTrafficTSCInstances = randomTrafficAnalysis,
  //      filteredMutantFailures = filteredMutantFailures,
  //  )

  //  CountOfScenariosKillingAMutantPerMutantPostEvaluation.evaluate(
  //      countOfKillingScenariosPerMutantFiltered =
  //          countOfScenariosKillingAMutantThatIsNotKilledByAllScenarios)
  //
  //  MutantKillingPostEvaluation.evaluate(
  //      failedMonitorMapping = failedMonitorMapping,
  //      monitorCombinations = monitorCombinations,
  //      mutantIds = mutantIds,
  //      identifier = "mutant_killing")
  //  MutantKillingPostEvaluation.evaluate(
  //      failedMonitorMapping = failedMonitorMapping,
  //      monitorCombinations = monitorCombinations,
  //      mutantIds = distinctMutantIds,
  //      identifier = "mutant_killing_without_duplicates")
  println("Finished!")
}

fun Set<MonitorViolation>.toFileNameSuffix(): String =
    this.sortedBy { it.name }.joinToString(separator = "_") { it.name }.ifBlank { "none" }

private fun <T> List<T>.allNonEmptySubsets(): List<Set<T>> {
  val result = mutableListOf<Set<T>>()
  val n = size

  for (mask in 1 until (1 shl n)) {
    val subset = buildSet {
      for (i in 0 until n) {
        if ((mask and (1 shl i)) != 0) {
          add(this@allNonEmptySubsets[i])
        }
      }
    }
    result += subset
  }

  return result
}

private fun buildFailedMonitorMappingQuery(): Query {
  val failedMonitors = MetricFailedMonitorsTable
  val joinedWithTSCInstances =
      failedMonitors.join(
          otherTable = MetricStartingValidTSCInstancesTable,
          onColumn = MetricFailedMonitorsTable.startingScenarioConfiguration,
          otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
          joinType = JoinType.LEFT)

  val fullQuery =
      joinedWithTSCInstances.select(
          MetricFailedMonitorsTable.mutant,
          MetricStartingValidTSCInstancesTable.tscInstance,
          MetricFailedMonitorsTable.startingScenarioConfiguration,
          MetricFailedMonitorsTable.monitorG0Failed,
          MetricFailedMonitorsTable.monitorG1Failed,
          MetricFailedMonitorsTable.monitorG2Failed,
          MetricFailedMonitorsTable.monitorG3Failed,
          MetricFailedMonitorsTable.monitorG4Failed,
          MetricFailedMonitorsTable.monitorG5Failed,
          MetricFailedMonitorsTable.monitorI1Failed,
          MetricFailedMonitorsTable.monitorI2Failed,
          MetricFailedMonitorsTable.monitorI3Failed)
  return fullQuery
}

private fun buildFailedMonitorMapping(query: Query): List<ScenarioFailure> {
  val result = mutableMapOf<UUID, MutableMap<UUID, MutableList<MutantFailures>>>()

  for (row in query) {
    val tscInstanceId = row[MetricStartingValidTSCInstancesTable.tscInstance].value
    val scenarioInstanceId = row[MetricFailedMonitorsTable.startingScenarioConfiguration].value
    val mutantId = row[MetricFailedMonitorsTable.mutant].value
    val violations = row.toMonitorViolations()

    val scenarios = result.getOrPut(tscInstanceId) { mutableMapOf() }
    val mutants = scenarios.getOrPut(scenarioInstanceId) { mutableListOf() }

    mutants += MutantFailures(mutantId = mutantId, violations = violations)
  }

  return result.map { (tscInstanceId, scenarios) ->
    ScenarioFailure(
        scenarioId = tscInstanceId,
        scenarioInstanceFailures =
            scenarios.map { (scenarioInstanceId, mutants) ->
              ScenarioInstanceFailures(scenarioInstanceId = scenarioInstanceId, mutants = mutants)
            })
  }
}

fun buildFailedMutantsMapping(): List<MutantFailure> =
    MetricFailedMonitorsTable.join(
            otherTable = MetricStartingValidTSCInstancesTable,
            onColumn = MetricFailedMonitorsTable.startingScenarioConfiguration,
            otherColumn = MetricStartingValidTSCInstancesTable.scenarioConfig,
            joinType = JoinType.INNER)
        .select(
            MetricStartingValidTSCInstancesTable.tscInstance,
            MetricFailedMonitorsTable.startingScenarioConfiguration,
            MetricFailedMonitorsTable.mutant,
            MetricFailedMonitorsTable.monitorG0Failed,
            MetricFailedMonitorsTable.monitorG1Failed,
            MetricFailedMonitorsTable.monitorG2Failed,
            MetricFailedMonitorsTable.monitorG3Failed,
            MetricFailedMonitorsTable.monitorG4Failed,
            MetricFailedMonitorsTable.monitorG5Failed,
            MetricFailedMonitorsTable.monitorI1Failed,
            MetricFailedMonitorsTable.monitorI2Failed,
            MetricFailedMonitorsTable.monitorI3Failed)
        .mapNotNull {
          val monitorBitmask =
              (if (it[MetricFailedMonitorsTable.monitorG0Failed]) 1 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG1Failed]) 2 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG2Failed]) 4 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG3Failed]) 8 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG4Failed]) 16 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG5Failed]) 32 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorI1Failed]) 64 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorI2Failed]) 128 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorI3Failed]) 256 else 0)

          MutantFailure(
              tscInstance = it[MetricStartingValidTSCInstancesTable.tscInstance].value,
              startingScenarioConfigurationID =
                  it[MetricFailedMonitorsTable.startingScenarioConfiguration].value,
              mutantID = it[MetricFailedMonitorsTable.mutant].value,
              monitorBitmask = monitorBitmask)
        }
        .toList()

private fun buildMonitorCombinations(): MutableList<Set<MonitorViolation>> =
    MonitorViolation.entries
        .toList()
        .map { setOf(it) }
        .toMutableList()
        .apply {
          add(
              setOf(
                  MonitorViolation.G0Accidents,
                  MonitorViolation.G1SafeDistance,
                  MonitorViolation.G2UnnecessaryBraking,
                  MonitorViolation.G4TrafficFlow,
                  MonitorViolation.I2FasterThanLeftTraffic))
          add(
              setOf(
                  MonitorViolation.G0Accidents,
                  MonitorViolation.G1SafeDistance,
                  MonitorViolation.G2UnnecessaryBraking,
                  MonitorViolation.G3MaximumSpeedLimit,
                  MonitorViolation.G4TrafficFlow,
                  MonitorViolation.G5EmergencyBraking,
                  MonitorViolation.I1Stopping,
                  MonitorViolation.I2FasterThanLeftTraffic))
        }

private fun ResultRow.toMonitorViolations(): List<MonitorViolation> {
  val violations = mutableListOf<MonitorViolation>()

  if (this[MetricFailedMonitorsTable.monitorG0Failed]) violations += MonitorViolation.G0Accidents
  if (this[MetricFailedMonitorsTable.monitorG1Failed]) violations += MonitorViolation.G1SafeDistance
  if (this[MetricFailedMonitorsTable.monitorG2Failed])
      violations += MonitorViolation.G2UnnecessaryBraking
  if (this[MetricFailedMonitorsTable.monitorG3Failed])
      violations += MonitorViolation.G3MaximumSpeedLimit
  if (this[MetricFailedMonitorsTable.monitorG4Failed]) violations += MonitorViolation.G4TrafficFlow
  if (this[MetricFailedMonitorsTable.monitorG5Failed])
      violations += MonitorViolation.G5EmergencyBraking
  if (this[MetricFailedMonitorsTable.monitorI1Failed]) violations += MonitorViolation.I1Stopping
  if (this[MetricFailedMonitorsTable.monitorI2Failed])
      violations += MonitorViolation.I2FasterThanLeftTraffic
  if (this[MetricFailedMonitorsTable.monitorI3Failed])
      violations += MonitorViolation.I3DangerousCutIn

  return violations
}

fun calculateCountOfScenariosKillingMutant(
    filteredFailedMutantsMapping: List<MutantFailure>,
    distinctMutantIds: List<UUID>
): List<Pair<UUID, Int>> {
  val countOfScenariosKillingMutant: List<Pair<UUID, Int>> =
      distinctMutantIds
          .map { id ->
            id to
                filteredFailedMutantsMapping
                    .filter { it.mutantID == id }
                    .map { it.tscInstance }
                    .toSet()
                    .size
          }
          .sortedBy { it.second }

  return countOfScenariosKillingMutant
}
