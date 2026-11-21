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
import tools.aqua.stars.coverage.significance.postEvaluation.scenario.StartScenarioDraws
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Draw experiments over the starting scenarios (steps D and E of `docs/test-bed-plan.md`).
 *
 * @param args `--condition=<name>` of the tree runs (default `full`) and `--seed=<n>` (default 0).
 */
fun main(args: Array<String>) {
  val condition = args.firstOrNull { it.startsWith("--condition=") }?.substringAfter("=") ?: "full"
  val seed = CliArgs.optionalInt(args, "seed")?.toLong() ?: 0L
  DbBootstrap.connect(DbBootstrap.DbConfig(port = System.getenv("DB_PORT")?.toInt() ?: 5432))
  StartScenarioDraws.run(condition, seed)
}
