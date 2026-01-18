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

package tools.aqua.stars.coverage.significance.db.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing metric starting valid TSC instances.
 *
 * @property run Evaluation run.
 * @property tsc TSC.
 * @property tscInstance TSC instance.
 * @property scenarioConfig Scenario starting configuration.
 * @property createdAt Timestamp of when the entry was created.
 */
object MetricStartingValidTSCInstancesTable : UUIDTable("metric_starting_valid_tsc_instances") {
  val run =
      reference(
          name = "run_id",
          foreign = EvaluationRunsTable,
          onDelete = ReferenceOption.CASCADE,
          onUpdate = ReferenceOption.CASCADE)
  val tsc =
      reference(
          name = "tsc_id",
          foreign = TSCsTable,
          onDelete = ReferenceOption.CASCADE,
          onUpdate = ReferenceOption.CASCADE)
  val tscInstance =
      reference(
          name = "tsc_instance_id",
          foreign = TSCInstancesTable,
          onDelete = ReferenceOption.RESTRICT,
          onUpdate = ReferenceOption.CASCADE)
  val scenarioConfig =
      reference(
          name = "scenario_config_id",
          foreign = ScenarioStartingConfigurationTable,
          onDelete = ReferenceOption.RESTRICT,
          onUpdate = ReferenceOption.CASCADE)
  val createdAt = timestamp("created_at")

  init {
    index(true, run, tsc, scenarioConfig)

    index(false, run)
    index(false, tsc)
    index(false, tscInstance)
    index(false, scenarioConfig)
  }
}
