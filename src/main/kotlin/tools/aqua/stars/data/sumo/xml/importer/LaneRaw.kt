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

package tools.aqua.stars.data.sumo.xml.importer

/**
 * Raw lane record parsed from `.net.xml` before its parent edge is created.
 *
 * @property laneId Lane id.
 * @property laneIndex Lane index.
 * @property speedLimitMetersPerSecond Speed limit (m/s).
 * @property laneLengthMeters Lane length (m).
 * @property shapeRaw Raw shape string (`"x,y x,y ..."`) from XML.
 */
data class LaneRaw(
    val laneId: String,
    val laneIndex: Int,
    val speedLimitMetersPerSecond: Float,
    val laneLengthMeters: Float,
    val shapeRaw: String
)
