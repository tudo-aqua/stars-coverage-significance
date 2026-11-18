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
import tools.aqua.stars.coverage.significance.postEvaluation.ThresholdSweep
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Runs the threshold sweep (step A.2a of `docs/test-bed-plan.md`) on `scenario_mutant_outcomes`.
 *
 * @param args `--maxPoolLoss=<share>` (default 0.05).
 */
fun main(args: Array<String>) {
  val maxPoolLoss =
      CliArgs.optionalDouble(args, "maxPoolLoss") ?: ThresholdSweep.DEFAULT_MAX_POOL_LOSS
  DbBootstrap.connect(DbBootstrap.DbConfig(port = System.getenv("DB_PORT")?.toInt() ?: 5432))
  ThresholdSweep.run(maxPoolLoss)
}
