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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeLeafAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeMutantSplitsTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable
import tools.aqua.stars.coverage.significance.db.tables.DistinctMutantsTable
import tools.aqua.stars.coverage.significance.db.tables.EvaluationRunsTable
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficAnalysisJobsTable
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficLongTailTable
import tools.aqua.stars.coverage.significance.db.tables.HighwayTrafficScenariosTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.db.tables.MetricFirstTSCInstanceChangeTable
import tools.aqua.stars.coverage.significance.db.tables.MetricStartingValidTSCInstancesTable
import tools.aqua.stars.coverage.significance.db.tables.MetricTotalTickDifferenceTable
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
const val DB_HOST = "ls14-sting1.cs.tu-dortmund.de"
/** Default database connection parameters. */
const val DB_PORT = 6432

/** Database bootstrap utility to connect to the PostgreSQL database and create necessary tables. */
object DbBootstrap {

  /**
   * Configuration for connecting to the database. Values are read from environment variables if
   * set, otherwise default values are used.
   *
   * @property host Database host (default: [DB_HOST] or env var "DB_HOST")
   * @property port Database port (default: [DB_PORT] or env var "DB_PORT")
   * @property database Database name (default: [DB_NAME] or env var "DB_NAME")
   * @property user Database user (default: [DB_USER] or env var "DB_USER")
   * @property password Database password (default: [DB_PASSWORD] or env var "DB_PASSWORD")
   * @property maxPoolSize Maximum size of the Hikari connection pool (default: 1 or env var
   *   "DB_POOL_MAX")
   * @property minIdle Minimum
   */
  data class DbConfig(
      val host: String = System.getenv("DB_HOST") ?: DB_HOST,
      val port: Int = System.getenv("DB_PORT")?.toInt() ?: DB_PORT,
      val database: String = System.getenv("DB_NAME") ?: DB_NAME,
      val user: String = System.getenv("DB_USER") ?: DB_USER,
      val password: String = System.getenv("DB_PASSWORD") ?: DB_PASSWORD,
      // Keep these conservative for your multi-process + PgBouncer setup.
      val maxPoolSize: Int = System.getenv("DB_POOL_MAX")?.toInt() ?: 1,
      val minIdle: Int = System.getenv("DB_POOL_MIN_IDLE")?.toInt() ?: 0,
  )

  /** One pool per JVM process. */
  @Volatile private var dataSource: HikariDataSource? = null

  /** Cached Exposed Database instance (per JVM process). */
  @Volatile private var exposedDb: Database? = null

  /**
   * Connect to the database (idempotent). Initializes the Hikari pool once per JVM process.
   *
   * @return the Exposed [Database] handle.
   */
  fun connect(cfg: DbConfig = DbConfig()): Database {
    // Fast path
    exposedDb?.let {
      return it
    }

    synchronized(this) {
      exposedDb?.let {
        return it
      }

      val jdbcUrlBase = "jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}"
      // Ensure a schema is selected (Postgres requires a schema to create tables in).
      // Add currentSchema=public to the JDBC URL so Exposed / Postgres will use the public schema
      // by default.
      val jdbcUrl =
          if (jdbcUrlBase.contains("?")) jdbcUrlBase
          else
              "$jdbcUrlBase?prepareThreshold=0&preparedStatementCacheQueries=0&preparedStatementCacheSizeMiB=0&preferQueryMode=simple"

      val hikariCfg =
          HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = cfg.user
            password = cfg.password
            driverClassName = "org.postgresql.Driver"

            maximumPoolSize = cfg.maxPoolSize
            minimumIdle = cfg.minIdle

            // Exposed uses transactions; keep autocommit off
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Reasonable timeouts; fail fast rather than piling up threads
            connectionTimeout = 10_000
            validationTimeout = 5_000
            idleTimeout = 60_000
            maxLifetime = 10 * 60_000

            // Optional but often helpful
            addDataSourceProperty("tcpKeepAlive", "true")
            addDataSourceProperty("reWriteBatchedInserts", "true")
            addDataSourceProperty("prepareThreshold", "0")
            addDataSourceProperty("preparedStatementCacheQueries", "0")
            addDataSourceProperty("preparedStatementCacheSizeMiB", "0")
            addDataSourceProperty("preferQueryMode", "simple")
          }

      val ds = HikariDataSource(hikariCfg)
      dataSource = ds

      val db = Database.connect(ds)
      exposedDb = db

      TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

      // Ensure pool is closed on JVM shutdown
      Runtime.getRuntime().addShutdownHook(Thread { runCatching { ds.close() } })

      return db
    }
  }

  /**
   * Create/update the schema (idempotent).
   *
   * Requires [connect] to have been called before, otherwise throws.
   */
  fun createSchema() {
    check(exposedDb != null) {
      "Database is not connected. Call DbBootstrap.connect() before DbBootstrap.createSchema()."
    }

    transaction {
      createMissingTablesAndColumns(
          EvaluationRunsTable,
          HighwayTrafficScenariosTable,
          HighwayTrafficAnalysisJobsTable,
          TSCsTable,
          MutantsTable,
          ScenarioStartingConfigurationTable,
          TSCInstancesTable,
          MetricFirstTSCInstanceChangeTable,
          MetricStartingValidTSCInstancesTable,
          MetricFailedMonitorsTable,
          MutantScenarioChunkJobsTable,
          MetricTotalTickDifferenceTable,
          DistinctMutantsTable,
          HighwayTrafficLongTailTable,
          DecisionTreeRunsTable,
          DecisionTreeMutantSplitsTable,
          DecisionTreeLeafAssignmentsTable)

      exec(
          """
          CREATE MATERIALIZED VIEW IF NOT EXISTS mutant_scenario_g0_violations AS
          SELECT "mutant_id",
                 "scenario_config_id",
                 COALESCE(BOOL_OR("next_tick_monitor_g0_Accidents_failed"), false) AS any_g0_violation
          FROM metric_failed_monitors
          GROUP BY "mutant_id", "scenario_config_id"
          """
              .trimIndent())

      exec(
          """
          CREATE MATERIALIZED VIEW IF NOT EXISTS scenario_mutant_kill_count AS
          SELECT scenario_config_id,
                 SUM(CASE WHEN any_g0_violation THEN 1 ELSE 0 END) AS mutants_killed
          FROM mutant_scenario_g0_violations
          GROUP BY scenario_config_id
          """
              .trimIndent())

      exec(
          """
          CREATE MATERIALIZED VIEW IF NOT EXISTS dc_startingscenario_mutant_combination AS
          SELECT metric_failed_monitors.id,
                 metric_failed_monitors.tick,
                 metric_failed_monitors.mutant_id,
                 metric_failed_monitors.scenario_config_id,
                 decision_tree_leaf_assignments.leaf_node_id,
                 mutant_scenario_g0_violations.any_g0_violation
          FROM metric_failed_monitors
                   JOIN decision_tree_leaf_assignments
                        ON metric_failed_monitors.id = decision_tree_leaf_assignments.metric_failed_monitor_id
                   JOIN mutant_scenario_g0_violations
                        ON metric_failed_monitors.mutant_id = mutant_scenario_g0_violations.mutant_id
                       AND metric_failed_monitors.scenario_config_id = mutant_scenario_g0_violations.scenario_config_id
          WHERE decision_tree_leaf_assignments.run_id = 3
          """
              .trimIndent())
    }
  }

  /**
   * Convenience: connect (if needed) and create schema (idempotent).
   *
   * @return the Exposed [Database] handle.
   */
  fun connectAndCreateSchema(cfg: DbConfig = DbConfig()): Database {
    val db = connect(cfg)
    createSchema()
    return db
  }
}
