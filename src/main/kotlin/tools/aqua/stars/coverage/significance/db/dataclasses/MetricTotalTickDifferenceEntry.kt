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

package tools.aqua.stars.coverage.significance.db.dataclasses

import tools.aqua.stars.coverage.significance.db.tables.MetricTotalTickDifferenceTable

/**
 * Data class representing an entry in the [MetricTotalTickDifferenceTable].
 *
 * @property id The unique identifier of the entry. This is optional and can be null when inserting
 *   a new entry.
 * @property runId The unique identifier of the test run for which the metric is calculated.
 * @property scenarioConfigId The unique identifier of the scenario configuration for which the
 *   metric is calculated.
 * @property mutantId The unique identifier of the mutant for which the metric is calculated.
 * @property totalTickDifferenceMillis The total tick difference in milliseconds for the given TSC,
 *   test run, scenario configuration, and mutant.
 */
data class MetricTotalTickDifferenceEntry(
    val id: Int? = null,
    val runId: Int,
    val scenarioConfigId: Int,
    val mutantId: Int,
    val totalTickDifferenceMillis: Long
)
