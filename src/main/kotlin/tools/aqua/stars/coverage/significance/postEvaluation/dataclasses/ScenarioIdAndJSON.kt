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

/**
 * Data class for storing a scenario ID and its JSON representation.
 *
 * @property scenarioInstanceId Scenario instance ID.
 * @property scenarioInstanceJson Scenario instance JSON.
 */
data class ScenarioIdAndJSON(
    val scenarioInstanceId: ScenarioInstanceId,
    val scenarioInstanceJson: ScenarioInstanceJSON
) {
  /**
   * Checks if the scenario instance has a specific feature.
   *
   * @param feature Feature to check for.
   * @return True if the scenario instance has the feature, false otherwise.
   */
  fun hasFeature(feature: String): Boolean = scenarioInstanceJson.contains("\"label\":\"$feature\"")
}
