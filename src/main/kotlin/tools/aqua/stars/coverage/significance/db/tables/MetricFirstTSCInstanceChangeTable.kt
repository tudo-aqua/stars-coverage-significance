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
 * Table for storing the first change of a TSC instance in a scenario starting configuration.
 *
 * @property run Evaluation run.
 * @property tsc TSC.
 * @property mutant Mutant.
 * @property scenarioConfig Scenario starting configuration.
 * @property firstChangeMillis Milliseconds since the epoch of the first change in the TSC instance.
 * @property createdAt Timestamp of when the entry was created.
 */
object MetricFirstTSCInstanceChangeTable : UUIDTable("metric_first_tsc_instance_changes") {
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
  val mutant = reference("mutant_id", MutantsTable, onDelete = ReferenceOption.CASCADE)
  val scenarioConfig =
      reference(
          name = "scenario_config_id",
          foreign = ScenarioStartingConfigurationTable,
          onDelete = ReferenceOption.CASCADE,
          onUpdate = ReferenceOption.CASCADE)
  val firstChangeMillis = long("first_change_millis").nullable()
  val createdAt = timestamp("created_at")

  init {
    index(true, run, tsc, scenarioConfig, mutant)

    index(false, run)
    index(false, tsc)
    index(false, scenarioConfig)
    index(false, firstChangeMillis)
    index(false, createdAt)
  }
}
