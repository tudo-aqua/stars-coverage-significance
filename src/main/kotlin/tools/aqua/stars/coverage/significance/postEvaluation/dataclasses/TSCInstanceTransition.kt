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
 * Aggregated transition between two TSC instances, counted across all (mutant, scenario, tick)
 * tuples where the TSC instance changed from [fromInstanceId] to [toInstanceId].
 *
 * @property fromInstanceId The TSC instance that was active on the previous tick.
 * @property toInstanceId The TSC instance that became active on the current tick.
 * @property totalCount Number of tick-rows that represent this transition.
 * @property monitorCounts Number of tick-rows where each monitor failed at the destination instance.
 */
data class TSCInstanceTransition(
    val fromInstanceId: TSCInstanceId,
    val toInstanceId: TSCInstanceId,
    val totalCount: Long,
    val monitorCounts: Map<MonitorViolation, Long>,
)
