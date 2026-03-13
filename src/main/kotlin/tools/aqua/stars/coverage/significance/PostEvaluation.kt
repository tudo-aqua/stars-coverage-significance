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
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.postEvaluation.boxPlots.MonitorViolation
import tools.aqua.stars.coverage.significance.postEvaluation.boxPlots.MutantFailures
import tools.aqua.stars.coverage.significance.postEvaluation.boxPlots.MutantKillingWithoutDuplicates
import tools.aqua.stars.coverage.significance.postEvaluation.boxPlots.ScenarioFailure
import tools.aqua.stars.coverage.significance.postEvaluation.boxPlots.ScenarioInstanceFailures

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  //  CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.evaluate()
  //  CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.evaluate()
  //  ScenarioInstancesLongTailDistributionPostEvaluation.evaluate()
  //  KilledMutantsPerMonitorPerScenarioPostEvaluation.evaluate()
  //  TotalNumberOfFailedMonitorsPerMonitorPostEvaluation.evaluate()
  //  TotalNumberOfFailedMonitorsPerScenarioPostEvaluation.evaluate()
  //  TotalNumberOfMutantsKilledPerScenarioPostEvaluation.evaluate()
  //  TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.evaluate()

  DbBootstrap.connect(DbBootstrap.DbConfig(port = 5432))
  var failedMonitorMapping: List<ScenarioFailure> = emptyList()
  var mutantIds = emptyList<UUID>()
  db {
    failedMonitorMapping = buildFailedMonitorMapping(buildFailedMonitorMappingQuery())
    mutantIds = MutantsRepository.getAllIds()
  }

  val monitorCombinations: MutableList<Set<MonitorViolation>> =
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
  println("Finished loading MonitorViolations from DB: ${failedMonitorMapping.size}")

  //          .allNonEmptySubsets()
  //          .sortedWith(
  //              compareBy<Set<MonitorViolation>> { it.size }
  //                  .thenBy { set -> set.map { it.name }.sorted().joinToString("_") })

  MutantKillingWithoutDuplicates.evaluate(failedMonitorMapping, monitorCombinations)
  //  MutantKilling.evaluate(failedMonitorMapping, monitorCombinations, mutantIds)
  //  LongTailAwareMutantKilling.evaluate(failedMonitorMapping, monitorCombinations, mutantIds.size)
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
