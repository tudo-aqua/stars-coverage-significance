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
 * Data class representing a row in the `decision_tree_leaf_assignments` table.
 *
 * @property runId Unique identifier of the decision tree run this assignment belongs to.
 * @property metricFailedMonitorId Unique identifier of the annotated metric entry.
 * @property leafNodeId Leaf node index assigned by the decision tree classifier for this row.
 */
data class DecisionTreeLeafAssignmentEntry(
    val runId: Int,
    val metricFailedMonitorId: Int,
    val leafNodeId: Int,
)
