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

import tools.aqua.stars.data.sumo.routeData.RoutesFile
import tools.aqua.stars.data.sumo.staticData.RoadNetwork

/**
 * Container for the imported SUMO scenario.
 *
 * @property net Parsed SUMO network.
 * @property routes Parsed SUMO routes file.
 * @property ticks Ordered ticks; they are linked via [TimeStep.previousTick] / [TimeStep.nextTick].
 * @property warnings Non-fatal issues encountered during import (e.g., missing attributes).
 */
data class Scenario(
    val net: RoadNetwork,
    val routes: RoutesFile,
    val ticks: List<TimeStep>,
    val warnings: List<String>
)
