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

package tools.aqua.stars.coverage.significance.postEvaluation.dataclasses

import tools.aqua.stars.coverage.significance.utils.MonitorViolation

/**
 * Aggregated result for a single (mutant, scenarioConfiguration) pair.
 *
 * @property mutantId Unique identifier of the mutant.
 * @property scenarioConfigId Unique identifier of the scenario starting configuration.
 * @property millisUntilFirstChange Milliseconds from the first observed tick until the TSC instance
 *   first changed, or null if no change was observed during the scenario.
 * @property failedMonitorsUntilChange Union of all monitors that failed at any tick from the
 *   scenario start up to (and including) the first TSC instance change, or across the entire
 *   observation window if no change occurred.
 */
data class TSCInstanceChangeData(
    val mutantId: Int,
    val scenarioConfigId: Int,
    val millisUntilFirstChange: Long?,
    val failedMonitorsUntilChange: Set<MonitorViolation>,
)
