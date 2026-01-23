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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.evaluation.VariablePredicate.Companion.predicate
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/** Predicate for checking whether two vehicles collide with each other. */
val collidesWith =
    predicate<TimeStep, Pair<Vehicle, Vehicle>>("Collides With") { tick, (ego, otherVehicle) ->
      tick.collisionsInTick.any { collision ->
        (collision.colliderVehicle == ego && collision.victimVehicle == otherVehicle) ||
            (collision.colliderVehicle == otherVehicle && collision.victimVehicle == ego)
      }
    }

/** General Traffic Rules: G_0 Accidents - Predicate implementation. */
val g0Accidents =
    SumoPredicate("G_0 Accidents") { tick ->
      tick.vehiclesInTick.all { otherVehicle ->
        otherVehicle != tick.ego && !collidesWith.holds(tick, tick.ego to otherVehicle)
      }
    }
