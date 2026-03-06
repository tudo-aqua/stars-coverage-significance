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

import tools.aqua.stars.coverage.significance.postEvaluation.CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.KilledMutantsPerMonitorPerScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.MutantKilling
import tools.aqua.stars.coverage.significance.postEvaluation.TSCInstancesLongTailDistributionPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfFailedMonitorsPerMonitorPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfFailedMonitorsPerScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfMutantsKilledPerScenarioPostEvaluation
import tools.aqua.stars.coverage.significance.postEvaluation.TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  MutantKilling.evaluate()
  CountOfScenarioInstancesWhereMonitorsFailedPerMonitorPerMutantPostEvaluation.evaluate()
  CountOfScenariosWhereMonitorsFailedPerMonitorPostEvaluation.evaluate()
  TSCInstancesLongTailDistributionPostEvaluation.evaluate()
  KilledMutantsPerMonitorPerScenarioPostEvaluation.evaluate()
  TotalNumberOfFailedMonitorsPerMonitorPostEvaluation.evaluate()
  TotalNumberOfFailedMonitorsPerScenarioPostEvaluation.evaluate()
  TotalNumberOfMutantsKilledPerScenarioPostEvaluation.evaluate()
  TotalNumberOfScenariosWithAtLeastOneFailedMonitorPerMutantPostEvaluation.evaluate()
}
