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

import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.postEvaluation.OutcomeScope
import tools.aqua.stars.coverage.significance.postEvaluation.ScenarioMutantOutcomes
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Builds `scenario_mutant_outcomes` (step A.1 of `docs/test-bed-plan.md`).
 *
 * @param args `--scope=baseline|mutants|all` (default `baseline`, so a forgotten flag never starts
 *   the long mutant run) and `--laneChangeWindowTicks=<n>` (default 20).
 */
fun main(args: Array<String>) {
  val scopeArg = args.firstOrNull { it.startsWith("--scope=") }?.substringAfter("=") ?: "baseline"
  val scope = OutcomeScope.valueOf(scopeArg.uppercase())
  val window =
      CliArgs.optionalInt(args, "laneChangeWindowTicks")
          ?: ScenarioMutantOutcomes.DEFAULT_LANE_CHANGE_WINDOW_TICKS

  // Direct port by default, not PgBouncer: the insert runs for hours in one transaction.
  DbBootstrap.connect(DbBootstrap.DbConfig(port = System.getenv("DB_PORT")?.toInt() ?: 5432))
  ScenarioMutantOutcomes.build(scope, window)
}
