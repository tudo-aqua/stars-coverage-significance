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
 * Data class representing a row in the TSC instances table.
 *
 * @property id Unique identifier of the TSC instance.
 * @property tscId Unique identifier of the TSC.
 * @property createdAt Timestamp of when the TSC instance was created.
 * @property instanceHash Hash of the TSC instance.
 * @property instanceJson JSON representation of the TSC instance.
 */
data class TSCInstanceEntry(
    val id: UUID? = null,
    val tscId: UUID,
    val createdAt: Instant,
    val instanceHash: String,
    val instanceJson: String,
)
