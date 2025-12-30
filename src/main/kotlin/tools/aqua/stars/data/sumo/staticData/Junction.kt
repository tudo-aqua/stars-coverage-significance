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
 * A SUMO junction (node) from `.net.xml`.
 *
 * @property junctionId Junction id.
 * @property junctionType Junction type (e.g., "dead_end", "priority").
 * @property x X coordinate.
 * @property y Y coordinate.
 * @property incomingLaneIds Incoming lane ids (may be empty).
 * @property internalLaneIds Internal lane ids (may be empty).
 * @property shape Geometry polyline.
 */
data class Junction(
    val junctionId: String,
    val junctionType: String,
    val x: Float,
    val y: Float,
    val incomingLaneIds: List<String>,
    val internalLaneIds: List<String>,
    val shape: List<Point>
)
