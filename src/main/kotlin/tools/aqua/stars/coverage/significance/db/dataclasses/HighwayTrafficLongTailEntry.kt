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

/**
 * Data class representing a row in the HighwayTrafficLongTailTable.
 *
 * @property id The unique identifier of the entry. This is optional and can be null when inserting
 *   a new entry.
 * @property tscInstanceId The unique identifier of the TSC instance.
 * @property tscInstanceJson The JSON representation of the TSC instance.
 * @property longTailValue The long tail value for the TSC instance.
 * @property createdAt The timestamp of when the entry was created.
 */
data class HighwayTrafficLongTailEntry(
    val id: UUID? = null,
    val tscInstanceId: UUID,
    val tscInstanceJson: String,
    val longTailValue: Long,
    val createdAt: Instant
)
