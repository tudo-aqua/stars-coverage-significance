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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.evaluation.NullaryPredicate.Companion.predicate
import tools.aqua.stars.data.av.dataclasses.Actor
import tools.aqua.stars.data.av.dataclasses.TickData
import tools.aqua.stars.data.av.dataclasses.TickDataDifferenceSeconds
import tools.aqua.stars.data.av.dataclasses.TickDataUnitSeconds
import tools.aqua.stars.data.av.dataclasses.TrafficLightState

val isOnMultilaneRoad =
    predicate<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>(
        "Ego is on multilane road") {
          it.ego.lane.road.lanes.size == 1
        }

val isOnRoad19 =
    predicate<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>(
        "Ego is on road 19") {
          it.ego.lane.road.id == 19
        }

val isOnRoad18 =
    predicate<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>(
        "Ego is on road 18") {
          it.ego.lane.road.id == 18
        }

val hasTrafficLight =
    predicate<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>(
        "Ego has traffic light") {
          it.ego.lane.hasTrafficLight
        }

val hasRedTrafficLight =
    predicate<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>(
        "Ego has red traffic light") { tick ->
          hasTrafficLight.holds(tick) &&
              tick.ego.lane.trafficLights.any { it.getStateInTick(tick) == TrafficLightState.Red }
        }
