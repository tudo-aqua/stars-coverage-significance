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

package tools.aqua.stars.coverage.significance.db

import java.sql.Connection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.EvaluationRunsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFirstTSCInstanceChangeTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.db.tables.TSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.TSCsTable

object DbBootstrap {

  data class DbConfig(
      val host: String = System.getenv("DB_HOST") ?: "localhost",
      val port: Int = (System.getenv("DB_PORT") ?: "5432").toInt(),
      val database: String = System.getenv("DB_NAME") ?: "stars",
      val user: String = System.getenv("DB_USER") ?: "stars",
      val password: String = System.getenv("DB_PASSWORD") ?: "stars",
  )

  fun connectAndCreateSchema(cfg: DbConfig = DbConfig()) {
    val jdbcUrl = "jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}"

    Database.connect(
        url = jdbcUrl, driver = "org.postgresql.Driver", user = cfg.user, password = cfg.password)

    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

    transaction {
      createMissingTablesAndColumns(
          EvaluationRunsTable,
          TSCsTable,
          ScenarioStartingConfigurationTable,
          TSCInstancesTable,
          MetricFirstTSCInstanceChangeTable,
          MetricStartingValidTSCInstancesTable)
    }
  }
}
