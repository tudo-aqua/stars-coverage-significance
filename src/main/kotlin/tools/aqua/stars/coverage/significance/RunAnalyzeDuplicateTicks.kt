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
import tools.aqua.stars.coverage.significance.postEvaluation.DuplicateTicksAnalysis

fun main() {
  // maxPoolSize must be >= the parallelism used by
  // MetricFailedMonitorsTable.buildDuplicateTickCompareColumns() (default 8), otherwise chunk
  // queries block waiting for a free connection instead of running concurrently. +1 as a small
  // safety margin beyond the exact minimum.
  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432, maxPoolSize = 9))
  DuplicateTicksAnalysis.evaluate()
  println("Finished!")
}
