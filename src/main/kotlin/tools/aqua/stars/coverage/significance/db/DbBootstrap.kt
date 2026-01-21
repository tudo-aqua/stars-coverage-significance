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

import java.lang.Thread.sleep
import java.sql.Connection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.EvaluationRunsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFirstTSCInstanceChangeTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.MutantScenarioChunkJobsTable
import tools.aqua.stars.coverage.significance.db.tables.MutantsTable
import tools.aqua.stars.coverage.significance.db.tables.ScenarioStartingConfigurationTable
import tools.aqua.stars.coverage.significance.db.tables.TSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.TSCsTable

/** Default database connection parameters. */
const val DB_NAME = "stars"
/** Default database credentials. */
const val DB_USER = DB_NAME
/** Default database credentials. */
const val DB_PASSWORD = DB_NAME
/** Default database connection parameters. */
const val DB_HOST = "localhost"
/** Default database connection parameters. */
const val DB_PORT = 5432

/** Database bootstrap utility to connect to the PostgreSQL database and create necessary tables. */
object DbBootstrap {

  /**
   * Database configuration.
   *
   * @property host Database host.
   * @property port Database port.
   * @property database Database name.
   * @property user Database user.
   * @property password Database password.
   */
  data class DbConfig(
      val host: String = System.getenv("DB_HOST") ?: DB_HOST,
      val port: Int = System.getenv("DB_PORT")?.toInt() ?: DB_PORT,
      val database: String = System.getenv("DB_NAME") ?: DB_NAME,
      val user: String = System.getenv("DB_USER") ?: DB_USER,
      val password: String = System.getenv("DB_PASSWORD") ?: DB_PASSWORD,
  )

  /**
   * Connects to the PostgreSQL database and creates necessary tables.
   *
   * @param cfg Database configuration.
   */
  fun connectAndCreateSchema(cfg: DbConfig = DbConfig()) {
    val jdbcUrl = "jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}"

    Database.connect(
        url = jdbcUrl, driver = "org.postgresql.Driver", user = cfg.user, password = cfg.password)

    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

    transaction {
      createMissingTablesAndColumns(
          EvaluationRunsTable,
          TSCsTable,
          MutantsTable,
          ScenarioStartingConfigurationTable,
          TSCInstancesTable,
          MetricFirstTSCInstanceChangeTable,
          MetricStartingValidTSCInstancesTable,
          MutantScenarioChunkJobsTable,
      )
    }
    sleep(2000)
  }
}
