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
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.postEvaluation.CountOfMutantsKilledPerScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.CountOfScenariosKillingAMutantPerMutantPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.MutantKillingPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.ScenarioByScenarioCrossTable
import tools.aqua.stars.coverage.significance.postEvaluation.ScenarioInstancesLongTailDistributionPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfFailedMonitorsPerMonitorPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfFailedMonitorsPerScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioInstanceFailures

typealias ScenarioInstanceId = UUID

typealias ScenarioInstanceJSON = String

typealias HighwayTrafficScenarioInstanceId = UUID

data class ScenarioIdAndJSON(
    val scenarioId: ScenarioInstanceId,
    val scenarioJson: ScenarioInstanceJSON
)

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.evaluate()
  CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.evaluate()
  ScenarioInstancesLongTailDistributionPostEvaluation.evaluate()
  TotalNumberOfFailedMonitorsPerMonitorPostEvaluation.evaluate()
  TotalNumberOfFailedMonitorsPerScenarioPostEvaluation.evaluate()
  TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.evaluate()

  DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
  var failedMonitorMapping: List<ScenarioFailure> = emptyList()
  var filteredMutantFailures: List<MutantFailure> =
      emptyList() // Only evaluations with at least one failed monitor
  var monitorCombinations: List<Set<MonitorViolation>> = emptyList()
  var mutantIds = emptyList<UUID>()
  var distinctMutantIds = emptyList<UUID>()
  var allScenarioInstances = emptyList<ScenarioIdAndJSON>()
  var randomTrafficAnalysis = emptyList<HighwayTrafficScenarioInstanceId>()
  var scenarioIds = emptyList<UUID>()
  db {
    println("Start loading DB")
    //    failedMonitorMapping = buildFailedMonitorMapping(buildFailedMonitorMappingQuery())
    distinctMutantIds = DistinctMutantsRepository.getAllIds()
    val failedMutantsMapping = buildFailedMutantsMapping()
    filteredMutantFailures = failedMutantsMapping.filter { it.mutantID in distinctMutantIds }
    //    monitorCombinations = buildMonitorCombinations()
    //    mutantIds = MutantsRepository.getAllIds()
    allScenarioInstances = TSCInstancesRepository.getAllScenariosWithJSON()
    randomTrafficAnalysis = HighwayTrafficScenariosRepository.getInstanceIds()
    scenarioIds = TSCInstancesRepository.getAllScenariosWithJSON().map { it.scenarioId }
  }
  println("Finished loading DB")

  val countOfScenariosKillingAMutant =
      calculateCountOfScenariosKillingMutant(
          filteredFailedMutantsMapping = filteredMutantFailures,
          distinctMutantIds = distinctMutantIds)

  val countOfScenariosKillingAMutantThatIsNotKilledByAllScenarios =
      countOfScenariosKillingAMutant.filter { it.second < 160 } // 160 = #TSC instances

  println("Finished calculating filtered count of killing scenarios per mutant")

  ScenarioByScenarioCrossTable.evaluate(
      filteredMutantFailures = filteredMutantFailures, scenarioIds = scenarioIds)

  // Plots where the different TSC features are located in the scatter-plot
  DistributionOfFeaturesInLongtailPostEvaluation.evaluate(
      allTSCInstances = allScenarioInstances, randomTrafficTSCInstances = randomTrafficAnalysis)

  CountOfScenariosKillingAMutantPerMutantPostEvaluation.evaluate(
      countOfKillingScenariosPerMutantFiltered =
          countOfScenariosKillingAMutantThatIsNotKilledByAllScenarios)

  MutantKillingPostEvaluation.evaluate(
      failedMonitorMapping = failedMonitorMapping,
      monitorCombinations = monitorCombinations,
      mutantIds = mutantIds,
      identifier = "mutant_killing")
  MutantKillingPostEvaluation.evaluate(
      failedMonitorMapping = failedMonitorMapping,
      monitorCombinations = monitorCombinations,
      mutantIds = distinctMutantIds,
      identifier = "mutant_killing_without_duplicates")
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
            MetricFailedMonitorsTable.monitorG4Failed,
            MetricFailedMonitorsTable.monitorI2Failed,
        )
        .mapNotNull {
          val monitorBitmask =
              (if (it[MetricFailedMonitorsTable.monitorG0Failed]) 16 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG1Failed]) 8 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG2Failed]) 4 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorG4Failed]) 2 else 0) +
                  (if (it[MetricFailedMonitorsTable.monitorI2Failed]) 1 else 0)

          if (monitorBitmask == 0) null
          else
              MutantFailure(
                  startingScenario = it[MetricStartingValidTSCInstancesTable.tscInstance].value,
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
                    .map { it.startingScenario }
                    .toSet()
                    .size
          }
          .sortedBy { it.second }

  return countOfScenariosKillingMutant
}
