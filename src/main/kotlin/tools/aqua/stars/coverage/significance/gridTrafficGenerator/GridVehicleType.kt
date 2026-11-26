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

package tools.aqua.stars.coverage.significance.gridTrafficGenerator

/**
 * Vehicle types of the 3x3-grid scenario generator. Every type except [EGO] is a driver profile;
 * [GridTrafficScenarioGenerator] chooses it from the vehicle's start speed.
 *
 * @property sumoId SUMO vType id in `sumoData/experiment/vTypes.add.xml`.
 * @property idLabel Short label used to build SUMO vehicle ids (see
 *   [tools.aqua.stars.coverage.significance.utils.getVehicleId]).
 * @property idCode One-letter code used in human-readable scenario ids (see
 *   [Spawn.getHumanReadableString]).
 */
enum class GridVehicleType(val sumoId: String, val idLabel: String, val idCode: Char) {
  /** The ego vehicle (AUT). */
  EGO("ego", "ego", 'E'),

  /** Careful driver: large gaps, strong keep-right discipline. */
  CALM("car_calm", "slow", 'C'),

  /** Normal driver: balanced lane changing. */
  NORMAL("car_normal", "normal", 'N'),

  /** Sporty driver: halfway between [NORMAL] and [SPEEDY]. */
  SPORTY("car_sporty", "sporty", 'P'),

  /** Careless driver: small gaps, weak keep-right discipline, assertive lane changes. */
  SPEEDY("car_speedy", "fast", 'S'),
}
