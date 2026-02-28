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

import java.util.UUID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.select
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable

object KillingsPerMonitorPostEvaluation {

  fun evaluate(): Map<String, List<TscKillingsRow>> {
    DbBootstrap.connect()
    val s = evaluateAllMonitors()
    return s
  }

  data class TscKillingsRow(
      val tscInstanceId: UUID, // adjust type to match your schema
      val killings: Long,
      val count: Long
  )

  fun killingsPerTscInstance(monitorColumn: Column<Boolean>): List<TscKillingsRow> = db {
    val mon = MetricFailedMonitorsTable
    val tsc = MetricStartingValidTSCInstancesTable // your table object

    // JOIN mon + tsc ON mon.scenario_config_id = tsc.scenario_config_id
    val joined =
        mon.join(
            otherTable = tsc,
            joinType = JoinType.INNER,
            additionalConstraint = { mon.startingScenarioConfiguration eq tsc.scenarioConfig })

    // count(distinct mon.mutant_id) AS killings
    val killingsExpr = mon.mutant.countDistinct()

    // count(tsc.scenario_config_id) AS count
    val countExpr = tsc.scenarioConfig.count()

    joined
        .select(tsc.tscInstance, killingsExpr, countExpr)
        .where { monitorColumn eq true }
        .groupBy(tsc.tscInstance)
        .orderBy(killingsExpr to SortOrder.DESC)
        .map { row ->
          TscKillingsRow(
              tscInstanceId = row[tsc.tscInstance].value,
              killings = row[killingsExpr],
              count = row[countExpr],
          )
        }
  }

  fun evaluateAllMonitors(): Map<String, List<TscKillingsRow>> = db {
    // List every monitor column explicitly (recommended; reflection is brittle).
    val monitors: List<Pair<String, Column<Boolean>>> =
        listOf(
            "monitor_g0_failed" to MetricFailedMonitorsTable.monitorG0Failed,
            "monitor_g1_failed" to MetricFailedMonitorsTable.monitorG1Failed,
            "monitor_g2_failed" to MetricFailedMonitorsTable.monitorG2Failed,
            "monitor_g22_failed" to MetricFailedMonitorsTable.monitorG22Failed,
            "monitor_g3_failed" to MetricFailedMonitorsTable.monitorG3Failed,
            "monitor_g4_failed" to MetricFailedMonitorsTable.monitorG4Failed,
            "monitor_i1_failed" to MetricFailedMonitorsTable.monitorI1Failed,
            "monitor_i2_failed" to MetricFailedMonitorsTable.monitorI2Failed,
            "monitor_i3_failed" to MetricFailedMonitorsTable.monitorI3Failed,
            "monitor_i4_failed" to MetricFailedMonitorsTable.monitorI4Failed,
        )

    monitors.associate { (name, col) -> name to killingsPerTscInstance(col) }
  }
}
