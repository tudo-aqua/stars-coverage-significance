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

import java.time.Instant
import java.util.UUID
import tools.aqua.stars.coverage.significance.db.tables.MetricFirstTSCInstanceChangeTable

/**
 * Data class representing a row in the [MetricFirstTSCInstanceChangeTable].
 *
 * @property id Unique identifier of the metric entry.
 * @property runId Unique identifier of the evaluation run.
 * @property tscId Unique identifier of the TSC.
 * @property scenarioConfigId Unique identifier of the scenario starting configuration.
 * @property firstChangeMillis The time in milliseconds after which the first change occurred.
 * @property createdAt Timestamp of when the metric entry was created.
 */
data class MetricFirstTSCInstanceChangeEntry(
    val id: UUID? = null,
    val runId: UUID,
    val tscId: UUID,
    val scenarioConfigId: UUID,
    val firstChangeMillis: Long?,
    val createdAt: Instant = Instant.now(),
)
