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
import tools.aqua.stars.coverage.significance.postEvaluation.TickReplayAnalysis
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * @param args First: a comma-separated list of `metric_failed_monitors.id` values to replay, e.g.
 *   `"123,456"`. Optionally followed by `--leadTimeSeconds=<comma separated values>` (e.g.
 *   `0.2,0.5,0.7,1.0`) to additionally replay each tick from progressively earlier starting points
 *   — see `TickReplayAnalysis`'s "Lead time" docs — writing one output file per lead time alongside
 *   the original (omit this flag) `tick_replay/` output.
 */
fun main(args: Array<String>) {
  require(args.isNotEmpty()) {
    "Usage: RunTickReplay <tickId>[,<tickId>...] [--leadTimeSeconds=<comma separated values>]"
  }
  val tickIds = args[0].split(",").map { it.trim().toInt() }
  val leadTimes = CliArgs.optionalDoubleList(args, "leadTimeSeconds")

  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))
  val passes: List<Double?> = leadTimes ?: listOf(null)
  passes.forEach { leadTimeSeconds -> TickReplayAnalysis.evaluate(tickIds, leadTimeSeconds) }
  println("Finished!")
}
