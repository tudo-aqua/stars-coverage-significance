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

package tools.aqua.stars.data.sumo.dataclasses.staticData

/**
 * Parsed location metadata from SUMO `.net.xml`.
 *
 * Contains numeric fields for boundaries/offset and a structured representation of the projection
 * parameter string (`projParameter`).
 *
 * @property netOffset Network offset parsed from `netOffset="x,y"`.
 * @property convertedBoundary Converted boundary parsed from `convBoundary="minX,minY,maxX,maxY"`.
 * @property originalBoundary Original boundary parsed from `origBoundary="minX,minY,maxX,maxY"`.
 * @property projection Projection definition parsed from `projParameter`.
 */
data class Location(
    val netOffset: Point,
    val convertedBoundary: BoundaryBox,
    val originalBoundary: BoundaryBox,
    val projection: Projection
)
