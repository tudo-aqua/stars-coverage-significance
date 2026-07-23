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
 * Compact columnar snapshot of the columns compared by
 * `DuplicateTicksAnalysis`/`MetricFailedMonitorsTable.buildDuplicateTickCompareColumns`: the row
 * [ids] plus one [FloatArray] per compared column. Stored column-major (one array per column)
 * instead of one object per row, to keep memory low for a full-table scan.
 *
 * @property ids Primary key (`metric_failed_monitors.id`) of each row, in column order.
 * @property columnNames Name of each compared column, matching the order of [columns].
 * @property columns One [FloatArray] (length == [ids].size) per compared column; `NaN` marks a
 *   database `NULL` (e.g. no vehicle in that grid cell) since primitive `FloatArray`s cannot hold
 *   `null` directly.
 */
class DuplicateTickColumns(
    val ids: IntArray,
    val columnNames: List<String>,
    val columns: Array<FloatArray>,
)
