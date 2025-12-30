/*
 * Copyright 2025 The STARS Coverage Significance Authors
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

package tools.aqua.stars.data.sumo.staticData

/**
 * A directed edge in the SUMO network.
 *
 * @property edgeId Edge id.
 * @property fromJunctionId Source junction id or empty string for internal edges.
 * @property toJunctionId Target junction id or empty string for internal edges.
 * @property edgeFunction Edge function (e.g., "internal" or empty string).
 * @property edgePriority Priority, 0 if absent.
 * @property lanes Lanes belonging to this edge.
 */
data class Edge(
    val edgeId: String,
    val fromJunctionId: String,
    val toJunctionId: String,
    val edgeFunction: String,
    val edgePriority: Int,
    val lanes: List<Lane>
)
