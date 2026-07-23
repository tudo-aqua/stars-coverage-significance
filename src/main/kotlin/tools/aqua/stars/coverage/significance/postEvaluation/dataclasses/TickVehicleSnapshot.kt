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

import kotlinx.serialization.Serializable

/**
 * Snapshot of one vehicle present at a tick, as recorded in
 * `metric_failed_monitors.all_vehicles_json` (a JSON array of these, one per vehicle present at
 * that tick — unlike the table's `surrounding*` columns, which only record the *nearest* vehicle in
 * each of 6 relative grid cells around the ego).
 *
 * @property id The vehicle's live SUMO vehicle id — identical to
 *   `tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle.vehicleId` in the `TimeStep` this
 *   snapshot was built from, so entries here can be cross-referenced against `vehiclesInTick` or
 *   the `collisionCollider`/`collisionVictim` `*VehicleId` columns.
 * @property ego Whether this entry is the ego vehicle. `true` only on the ego's own entry; omitted
 *   from the serialized JSON for every other entry (kotlinx.serialization's default-value
 *   omission).
 * @property type Live SUMO vehicle-type id (e.g. `"car_calm"`, `"car_normal"`, `"car_speedy"`,
 *   `"ego"`/`"mutant"`).
 * @property lane SUMO lane index (0=right .. 2=left).
 * @property front Front-bumper lane position (m).
 * @property back Back-bumper lane position (m); vehicle length = `front - back`.
 * @property speed Speed (m/s).
 * @property accel Acceleration (m/s²).
 */
@Serializable
data class TickVehicleSnapshot(
    val id: String,
    val ego: Boolean = false,
    val type: String,
    val lane: Int,
    val front: Float,
    val back: Float,
    val speed: Float,
    val accel: Float,
)
