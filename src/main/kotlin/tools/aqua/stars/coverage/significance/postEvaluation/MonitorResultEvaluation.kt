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

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable

object MonitorResultEvaluation {

  fun evaluate() {
    DbBootstrap.connect()
    db {
      val countMonitorG0 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG0Failed eq true)
              .count()
      println("Monitor G0: $countMonitorG0")
      val countMonitorG1 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG1Failed eq true)
              .count()
      println("Monitor G1: $countMonitorG1")
      val countMonitorG2 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG2Failed eq true)
              .count()
      println("Monitor G2: $countMonitorG2")
      val countMonitorG22 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG22Failed eq true)
              .count()
      println("Monitor G22: $countMonitorG22")
      val countMonitorG3 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG3Failed eq true)
              .count()
      println("Monitor G3: $countMonitorG3")
      val countMonitorG4 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorG4Failed eq true)
              .count()
      println("Monitor G4: $countMonitorG4")
      val countMonitorI1 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorI1Failed eq true)
              .count()
      println("Monitor I1: $countMonitorI1")
      val countMonitorI2 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorI2Failed eq true)
              .count()
      println("Monitor I2: $countMonitorI2")
      val countMonitorI3 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorI3Failed eq true)
              .count()
      println("Monitor I3: $countMonitorI3")
      val countMonitorI4 =
          MetricFailedMonitorsRepository.callPredicate(
                  MetricFailedMonitorsTable.monitorI4Failed eq true)
              .count()
      println("Monitor I4: $countMonitorI4")
    }
  }
}
