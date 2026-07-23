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

package tools.aqua.stars.data.sumo.libSumo

import kotlinx.serialization.decodeFromString
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration

/**
 * One vehicle to be placed when reconstructing a recorded tick.
 *
 * @property vehId SUMO vehicle id to use — reused directly from the original recording
 *   ([TickVehicleSnapshot.id]).
 * @property laneIndex SUMO lane index (0=right .. 2=left).
 * @property positionMeters Lane position (front bumper) to force-place the vehicle at.
 * @property speedMps Exact recorded speed to place the vehicle at (SUMO `departSpeed`). No
 *   acceleration field: SUMO has no reliable way to seed a vehicle's instantaneous acceleration —
 *   `vehicle.setAcceleration` only takes effect over subsequent simulated steps, and gets silently
 *   neutralized by any `vehicle.setSpeed` call on the same vehicle.
 * @property vehicleType Live SUMO vehicle-type id to spawn this vehicle as (e.g. `"mutant"` for
 *   ego, `"car_calm"`/`"car_normal"`/`"car_speedy"` for background vehicles).
 * @property isEgo Whether this placement is the ego vehicle.
 */
data class ReplayPlacement(
    val vehId: String,
    val laneIndex: Int,
    val positionMeters: Double,
    val speedMps: Double,
    val vehicleType: String,
    val isEgo: Boolean,
)

/**
 * Builds placements for every vehicle recorded in [tick]'s `all_vehicles_json` column — i.e. every
 * vehicle actually present in the simulation at that tick, not just the nearest one in each of the
 * 6 `surrounding*` grid cells.
 *
 * An earlier version derived placements from the `surrounding*` columns (either as ego-relative
 * offsets, or later directly from their absolute front-bumper positions), which only ever captured
 * up to 6 neighbours — a vehicle "blocked" from being nearest (e.g. two cars ahead in the same
 * lane) was silently missing. `all_vehicles_json` (populated by [FailedMonitorsMetric] since it was
 * added) records every vehicle present at the tick, so this reconstructs the full scene instead of
 * a 6-neighbour approximation of it.
 *
 * @param tick The recorded tick to reconstruct.
 * @return Placements for every vehicle recorded at that tick.
 */
fun computeReplayPlacements(tick: MetricFailedMonitorsEntry): List<ReplayPlacement> {
  val vehicles = jsonConfiguration.decodeFromString<List<TickVehicleSnapshot>>(tick.allVehiclesJson)
  check(vehicles.any { it.ego }) { "Tick ${tick.id}'s allVehiclesJson has no ego entry" }

  return vehicles.map { v ->
    ReplayPlacement(
        vehId = v.id,
        laneIndex = v.lane,
        positionMeters = v.front.toDouble(),
        speedMps = v.speed.toDouble(),
        vehicleType = v.type,
        isEgo = v.ego,
    )
  }
}
