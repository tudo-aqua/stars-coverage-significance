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
 * Why a tick's backward lead-time sweep (see
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis`) stopped.
 */
@Serializable
enum class DivergenceStopReason {
  /** The replayed original mutant no longer reproduces the recorded G0 failure. */
  DIVERGED,
  /** The ego left the simulation before a next tick could be evaluated. */
  EGO_LEFT_SIMULATION,
  /** No earlier recorded tick exists for this (run, scenario, mutant) to step back to. */
  REACHED_SCENARIO_START,
}

/**
 * One step of a tick's backward lead-time sweep.
 *
 * @property leadTimeSeconds How far before the original tick this step reconstructed the scene from
 *   (0.0, 0.1, 0.2, ...).
 * @property startTickId `metric_failed_monitors.id` of the recorded tick the scene was
 *   reconstructed from for this step.
 * @property stepCount Number of simulation steps replayed from [startTickId] through to one past
 *   the original tick.
 * @property g0Failed Whether the original mutant's replay still reproduces the recorded G0 failure
 *   at this lead time.
 */
@Serializable
data class LeadTimeStepResult(
    val leadTimeSeconds: Double,
    val startTickId: Long,
    val stepCount: Int,
    val g0Failed: Boolean,
)

/**
 * Backward lead-time sweep outcome for one flagged tick, replaying only its own original mutant
 * (see `tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis`).
 *
 * @property tickId `metric_failed_monitors.id` of the analyzed tick.
 * @property originalTick `metric_failed_monitors.tick` value of the analyzed tick.
 * @property runId Evaluation run the analyzed tick belongs to.
 * @property scenarioConfigId Scenario starting configuration the analyzed tick belongs to.
 * @property originalMutantId Id of the mutant that originally produced this tick — the only mutant
 *   replayed throughout the sweep.
 * @property steps One entry per lead time actually replayed, in increasing order of
 *   [LeadTimeStepResult.leadTimeSeconds], up to (and including, if [stopReason] is
 *   [DivergenceStopReason.DIVERGED]) the step that stopped the sweep.
 * @property divergenceLeadTimeSeconds The lead time at which the replay first stopped reproducing
 *   the recorded failure, or `null` unless [stopReason] is [DivergenceStopReason.DIVERGED].
 * @property stopReason Why the sweep stopped — see [DivergenceStopReason].
 */
@Serializable
data class TickG0DivergenceResult(
    val tickId: Long,
    val originalTick: Long,
    val runId: Int,
    val scenarioConfigId: Int,
    val originalMutantId: Int,
    val steps: List<LeadTimeStepResult>,
    val divergenceLeadTimeSeconds: Double?,
    val stopReason: DivergenceStopReason,
)

/**
 * Aggregate summary across an entire `G0DivergenceLeadTimeAnalysis` run, built by reading back
 * every worker's streamed [TickG0DivergenceResult] detail file.
 *
 * @property runId Evaluation run id the analysis was restricted to, or `null` if it covered every
 *   run.
 * @property totalTicksAnalyzed Number of flagged ticks the analysis swept.
 * @property divergedCount Ticks whose sweep ended with [DivergenceStopReason.DIVERGED].
 * @property immediateDivergenceCount Of [divergedCount], how many diverged already at lead time 0.0
 *   — i.e. the original mutant's replay doesn't even reproduce the recorded failure with no lead
 *   time at all. Not a lead-time signal (there's no lead time *before* which it still worked),
 *   called out separately so it doesn't skew a threshold picked from [histogram] / [percentiles].
 * @property egoLeftSimulationCount Ticks whose sweep ended with
 *   [DivergenceStopReason.EGO_LEFT_SIMULATION].
 * @property reachedScenarioStartCount Ticks whose sweep ended with
 *   [DivergenceStopReason.REACHED_SCENARIO_START] (the original mutant reproduces the failure at
 *   every available lead time, all the way back to the start of the recorded scenario).
 * @property divergenceLeadTimes One entry per [divergedCount] tick (including the
 *   [immediateDivergenceCount] zeros), for external plotting/filtering.
 * @property histogram [divergenceLeadTimes] grouped by exact value (already discrete 0.1s
 *   multiples) to tick count.
 * @property percentiles Nearest-rank percentiles ("p50", "p75", "p90", "p95", "p99") over
 *   [divergenceLeadTimes].
 */
@Serializable
data class G0DivergenceLeadTimeSummary(
    val runId: Int?,
    val totalTicksAnalyzed: Int,
    val divergedCount: Int,
    val immediateDivergenceCount: Int,
    val egoLeftSimulationCount: Int,
    val reachedScenarioStartCount: Int,
    val divergenceLeadTimes: List<Double>,
    val histogram: Map<Double, Int>,
    val percentiles: Map<String, Double>,
)
