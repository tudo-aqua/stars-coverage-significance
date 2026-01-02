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

package tools.aqua.stars.data.sumo.importer

/**
 * Raw collision record used for bucketing before resolving vehicle pointers per tick.
 *
 * @property tickMillis Tick in ms (derived from collision time).
 * @property timeSeconds Collision time in seconds.
 * @property laneId Lane id as given by SUMO.
 * @property positionOnLaneMeters Position on lane.
 * @property colliderId Collider vehicle id.
 * @property victimId Victim vehicle id.
 * @property collisionType Collision type (raw string).
 * @property rawAttributes All raw XML attributes.
 */
data class CollisionEventRaw(
    val tickMillis: Long,
    val timeSeconds: Float,
    val laneId: String,
    val positionOnLaneMeters: Float,
    val colliderId: String,
    val victimId: String,
    val collisionType: String,
    val rawAttributes: Map<String, String>
)
