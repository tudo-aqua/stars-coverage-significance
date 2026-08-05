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

/**
 * Pre-computed groupings derived from a tick list, shared across all evaluation strategies to avoid
 * redundant in-memory traversals when multiple functions operate on the same data.
 *
 * @param allTicks The tick list this data was derived from (full or filtered).
 * @param dtLeafGroups Ticks grouped by DC leaf node ID; empty when no leaf run exists.
 * @param accidentDCLeafGroups Subset of [dtLeafGroups] where at least one tick has a G0 failure.
 * @param allScenarioIds Flat list of scenario config IDs (tick-weighted) from [allTicks].
 * @param startingScenarioIdsPerDCLeafId Scenario config IDs extracted per DC leaf group.
 * @param startingScenarioIdsPerAccidentDCLeafId Scenario config IDs per accident DC leaf group.
 */
data class SamplingDataScenarioDrawing(
    val allTicks: List<NextTickPostEvaluationDatabaseEntry>,
    val dtLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>,
    val accidentDCLeafGroups: List<List<NextTickPostEvaluationDatabaseEntry>>,
    val allScenarioIds: Set<StartingScenarioId>,
    val accidentScenarioIds: Set<StartingScenarioId>,
    val startingScenarioIdsPerDCLeafId: List<Set<StartingScenarioId>>,
    val startingScenarioIdsPerAccidentDCLeafId: List<Set<StartingScenarioId>>,
    val accidentStartingScenarioIdsPerAccidentDCLeafId: List<Set<StartingScenarioId>>,
)
