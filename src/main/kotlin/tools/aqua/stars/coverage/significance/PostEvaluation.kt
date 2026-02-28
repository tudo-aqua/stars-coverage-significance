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

import tools.aqua.stars.coverage.significance.postEvaluation.FailedMonitorsCountPerMutantPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.FailedMonitorsCountPerStartingScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.ValidTSCInstancesEvaluation

/**
 * This utility runs the post-evaluation analysis for valid TSC instances. It can be run after all
 * evaluation runs have completed to analyze the results and determine the significance of the
 * coverage of valid TSC instances. It can be run multiple times without side effects, as it only
 * reads from the database and writes output files without modifying the database.
 */
fun main() {
  FailedMonitorsCountPerStartingScenarioPostEvaluation.evaluate()
  FailedMonitorsCountPerMutantPostEvaluation.evaluate()
  ValidTSCInstancesEvaluation.evaluate()
}
