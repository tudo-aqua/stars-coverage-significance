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

/** Directory paths for grid traffic scenarios. */
const val GRID_TRAFFIC_DIR = "sumo_data/gridTrafficScenarios"
/** Sub-directory for scenario files. */
const val SCENARIO_DIR = "$GRID_TRAFFIC_DIR/scenarios"
/** Sub-directory for exported SUMO files. */
const val EXPORT_DIR = "$GRID_TRAFFIC_DIR/export"
/** Sub-directory for collision files. */
const val COLLISION_DIR = "$GRID_TRAFFIC_DIR/collision"
/** File extension for scenario files. */
const val SCENARIO_FILE_EXTENSION = "rou.xml"
/** File extension for exported SUMO files. */
const val EXPORT_FILE_EXTENSION = "export.xml"
/** File extension for collision files. */
const val COLLISION_FILE_EXTENSION = "collisions.xml"
/** Number of parallel threads to use for experiment runs. */
val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
/** Number of scenarios to generate and evaluate in the main function. */
const val NUMBER_OF_SCENARIOS = 100
/** Seed for scenario generation and evaluation. Keep this constant to get reproducible results. */
const val SEED = 1
/** Size of the buffer (in seconds) to use when importing tick sequences. */
const val BUFFER_SIZE_IN_SECONDS = 10.0
/** When importing tick sequences, only take ticks at every X milliseconds. This reduces memory */
const val TAKE_ONLY_TICKS_AT_X_MILLIS = 250
/** Size of the buffer (in number of ticks) to use when importing tick sequences. */
const val BUFFER_SIZE = ((BUFFER_SIZE_IN_SECONDS * 1000) / TAKE_ONLY_TICKS_AT_X_MILLIS).toInt()
