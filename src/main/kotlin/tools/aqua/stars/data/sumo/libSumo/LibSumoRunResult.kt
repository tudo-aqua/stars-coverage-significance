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

import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep

/**
 * Result of a LibSUMO simulation run.
 *
 * @property ticks List of time steps recorded during the simulation.
 * @property warnings List of warning messages generated during the simulation.
 */
data class LibSumoRunResult(val ticks: List<TimeStep>, val warnings: List<String>)
