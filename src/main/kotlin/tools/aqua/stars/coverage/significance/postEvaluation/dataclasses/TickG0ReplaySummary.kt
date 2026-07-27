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
 * G0 (Accidents) replay outcome for one mutant substituted into one recorded tick (see
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis`).
 *
 * @property mutantId Unique identifier of the substituted mutant.
 * @property mutantNumber Mutant number (see `mutants.mutant_number`).
 * @property className Mutant class name.
 * @property isOriginalMutant Whether this is the mutant that originally produced the recorded tick
 *   (`metric_failed_monitors.mutant_id`), as opposed to one substituted in for comparison.
 * @property g0Failed Whether the G0 monitor failed (an ego-involved collision occurred) at the
 *   replayed next tick, or `null` if the replay was inconclusive (the ego left the simulation
 *   during the step, so no next tick exists to evaluate).
 */
@Serializable
data class TickMutantG0ReplayResult(
    val mutantId: Int,
    val mutantNumber: Int,
    val className: String,
    val isOriginalMutant: Boolean,
    val g0Failed: Boolean?,
)

/**
 * Aggregated G0 (Accidents) replay outcome for one recorded tick across every known mutant (see
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis`).
 *
 * @property tickId `metric_failed_monitors.id` of the replayed tick.
 * @property originalTick `metric_failed_monitors.tick` value of the replayed tick.
 * @property runId Evaluation run the replayed tick belongs to.
 * @property scenarioConfigId Scenario starting configuration the replayed tick belongs to.
 * @property originalMutantId Id of the mutant that originally produced this tick.
 * @property originalMutantFailed Whether replaying the original mutant reproduces the recorded
 *   failure, `null` if that replay was inconclusive.
 * @property newMutantsFailedCount Count of mutants *other than* [originalMutantId] for which
 *   [TickMutantG0ReplayResult.g0Failed] is `true`.
 * @property mutantResults Per-mutant replay outcome, one entry per known mutant.
 */
@Serializable
data class TickG0ReplaySummary(
    val tickId: Int,
    val originalTick: Long,
    val runId: Int,
    val scenarioConfigId: Int,
    val originalMutantId: Int,
    val originalMutantFailed: Boolean?,
    val newMutantsFailedCount: Int,
    val mutantResults: List<TickMutantG0ReplayResult>,
)
