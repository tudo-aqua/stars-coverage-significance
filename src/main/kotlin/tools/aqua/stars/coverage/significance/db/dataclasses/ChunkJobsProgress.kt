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

/**
 * Data class representing the progress of chunk jobs.
 *
 * @property total Total number of chunk jobs.
 * @property pending Number of pending chunk jobs.
 * @property running Number of running chunk jobs.
 * @property done Number of completed chunk jobs.
 * @property failed Number of failed chunk jobs.
 */
data class ChunkJobsProgress(
    val total: Long,
    val pending: Long,
    val running: Long,
    val done: Long,
    val failed: Long,
) {
  /** Total number of completed chunk jobs (done + failed). */
  val completed: Long
    get() = done + failed
}
