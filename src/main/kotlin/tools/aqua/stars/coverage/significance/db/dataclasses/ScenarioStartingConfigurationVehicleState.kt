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

package tools.aqua.stars.coverage.significance.db.dataclasses

/** Enum representing the possible vehicle states in a scenario starting configuration. */
enum class ScenarioStartingConfigurationVehicleState {
  /** No vehicle. */
  NONE,
  /** Ego vehicle. */
  EGO,
  /** Vehicle that is faster than the ego vehicle. */
  FASTER,
  /** Vehicle that is slower than the ego vehicle. */
  SLOWER,
  /** Vehicle that drives at about the same speed as the ego vehicle. */
  SAME_SPEED
}
