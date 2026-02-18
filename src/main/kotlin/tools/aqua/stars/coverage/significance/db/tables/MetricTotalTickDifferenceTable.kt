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

/**
 * Table for storing the total tick difference metric for a given TSC, test run, scenario
 * configuration, and mutant.
 *
 * @property tsc The reference to the TSC for which the metric is calculated.
 * @property run The reference to the test run for which the metric is calculated.
 * @property startingScenarioConfiguration The reference to the scenario configuration for which the
 *   metric is calculated.
 * @property mutant The reference to the mutant for which the metric is calculated.
 * @property totalTickDifferenceMillis The total tick difference in milliseconds for the given TSC,
 *   test run, scenario configuration, and mutant.
 */
object MetricTotalTickDifferenceTable : UUIDTable("metric_total_tick_difference") {
  val tsc =
      reference(
          name = "tsc_id",
          foreign = TSCsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val run =
      reference(
          name = "run_id",
          foreign = EvaluationRunsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val startingScenarioConfiguration =
      reference(
          name = "scenario_config_id",
          foreign = ScenarioStartingConfigurationTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
  val mutant =
      reference(
          name = "mutant_id",
          foreign = MutantsTable,
          onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE,
          onUpdate = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)

  val totalTickDifferenceMillis = long("total_tick_difference_millis").default(-1L)

  init {
    uniqueIndex(tsc, run, startingScenarioConfiguration, mutant)
  }
}
