/*
 * Copyright 2025-2026 The STARS Coverage Significance Authors
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

package tools.aqua.stars.data.sumo.dynamicData

import tools.aqua.stars.data.sumo.staticData.Edge
import tools.aqua.stars.data.sumo.staticData.Lane

/**
 * Collision event occurring in a tick.
 *
 * @property collisionTimeSeconds Collision time in seconds.
 * @property lane Lane where collision occurred (or placeholder lane).
 * @property edge Edge derived from [lane] (or placeholder edge).
 * @property positionOnLaneMeters Position on lane.
 * @property colliderVehicle Vehicle pointer (resolved to vehicle in same tick when possible, else
 *   placeholder).
 * @property victimVehicle Vehicle pointer (resolved to vehicle in same tick when possible, else
 *   placeholder).
 * @property collisionType Collision type string (empty if absent).
 * @property rawAttributes All original XML attributes.
 */
data class CollisionEvent(
    val collisionTimeSeconds: Float,
    val lane: Lane,
    val edge: Edge,
    val positionOnLaneMeters: Float,
    val colliderVehicle: Vehicle,
    val victimVehicle: Vehicle,
    val collisionType: String,
    val rawAttributes: Map<String, String>
)
