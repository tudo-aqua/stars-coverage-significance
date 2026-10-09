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
 * One step of a tick's *full* backward lead-time sweep (see
 * `tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis`).
 *
 * @property leadTimeSeconds How far before the original tick this step reconstructed the scene from
 *   (0.0, 0.1, 0.2, ...).
 * @property startTickId `metric_failed_monitors.id` of the recorded tick the scene was
 *   reconstructed from for this step.
 * @property stepCount Number of simulation steps replayed from [startTickId] through to one past
 *   the original tick.
 * @property g0Failed Whether the original mutant's replay still reproduces the recorded G0 failure
 *   at this lead time, or `null` if this *specific* lead time's replay was inconclusive (the ego
 *   left the simulation before reaching the original tick's moment) — unlike
 *   `tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DivergenceStopReason.EGO_LEFT_SIMULATION`,
 *   this does not stop the sweep: a different (earlier) starting point can still succeed.
 */
@Serializable
data class LeadTimeSweepStepResult(
    val leadTimeSeconds: Double,
    val startTickId: Long,
    val stepCount: Int,
    val g0Failed: Boolean?,
)

/**
 * Full backward lead-time sweep outcome for one flagged tick, replaying only its own original
 * mutant (see `tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis`)
 * — unlike
 * `tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickG0DivergenceResult`, the
 * sweep always continues all the way back to the start of the recorded scenario instead of stopping
 * at the first lead time that fails to reproduce the recorded failure, because a state-based
 * autopilot/mutant can plausibly reproduce it again at an even earlier lead time after failing to
 * at a nearer one (a different starting point is a different trajectory, not simply "more of the
 * same" with extra steps prepended).
 *
 * @property tickId `metric_failed_monitors.id` of the analyzed tick.
 * @property originalTick `metric_failed_monitors.tick` value of the analyzed tick.
 * @property runId Evaluation run the analyzed tick belongs to.
 * @property scenarioConfigId Scenario starting configuration the analyzed tick belongs to.
 * @property originalMutantId Id of the mutant that originally produced this tick — the only mutant
 *   replayed throughout the sweep.
 * @property steps Every lead time actually replayed, in increasing order of
 *   [LeadTimeSweepStepResult.leadTimeSeconds], from 0.0 through to the earliest recorded tick of
 *   this (run, scenario, mutant) — i.e. always the full sweep, never cut short.
 * @property minReproducingLeadTimeSeconds The smallest lead time at which the replay reproduces the
 *   recorded failure, or `null` if it never does (see [reproducingCount]).
 * @property maxReproducingLeadTimeSeconds The largest lead time at which the replay reproduces the
 *   recorded failure, or `null` if it never does. Together with [minReproducingLeadTimeSeconds],
 *   these answer "how close" and "how far back" the original mutant can be given lead time and
 *   still reproduce its own recorded failure — not just the first point of departure from
 *   reproducing it.
 * @property reproducingCount Of [steps], how many have `g0Failed == true`.
 * @property divergedCount Of [steps], how many have `g0Failed == false`.
 * @property inconclusiveCount Of [steps], how many have `g0Failed == null`.
 * @property isMonotonic Whether reproduction only ever turns off as lead time increases and never
 *   back on — `false` means some lead time reproduces the failure *after* a smaller lead time
 *   already failed to (inconclusive steps are ignored for this check) — direct evidence that the
 *   original mutant's (or the surrounding traffic's) state-based behavior doesn't decay smoothly
 *   with lead time. Vacuously `true` when [reproducingCount] or [divergedCount] is 0.
 */
@Serializable
data class TickG0FullSweepResult(
    val tickId: Long,
    val originalTick: Long,
    val runId: Int,
    val scenarioConfigId: Int,
    val originalMutantId: Int,
    val steps: List<LeadTimeSweepStepResult>,
    val minReproducingLeadTimeSeconds: Double?,
    val maxReproducingLeadTimeSeconds: Double?,
    val reproducingCount: Int,
    val divergedCount: Int,
    val inconclusiveCount: Int,
    val isMonotonic: Boolean,
)

/**
 * Per-mutant aggregate across an entire `G0FullLeadTimeSweepAnalysis` run — the same fields as
 * [G0FullLeadTimeSweepSummary], restricted to ticks whose own original mutant
 * (`metric_failed_monitors.mutant_id`) is [mutantId].
 *
 * @property mutantId Unique identifier of the mutant (`mutants.id`) every one of these ticks was
 *   originally produced by.
 * @property totalTicks Number of flagged ticks originally produced by this mutant.
 * @property neverReproducedCount Of [totalTicks], how many never reproduce the recorded failure at
 *   any lead time (`minReproducingLeadTimeSeconds == null`).
 * @property nonMonotonicCount Of [totalTicks], how many have `isMonotonic == false`.
 * @property minLeadTimeHistogram [TickG0FullSweepResult.minReproducingLeadTimeSeconds] grouped by
 *   exact value to tick count, over this mutant's ticks that reproduce at all.
 * @property maxLeadTimeHistogram [TickG0FullSweepResult.maxReproducingLeadTimeSeconds] grouped by
 *   exact value to tick count, over this mutant's ticks that reproduce at all.
 * @property minLeadTimePercentiles Nearest-rank percentiles ("p50", "p75", "p90", "p95", "p99")
 *   over this mutant's minimum reproducing lead times.
 * @property maxLeadTimePercentiles Nearest-rank percentiles ("p50", "p75", "p90", "p95", "p99")
 *   over this mutant's maximum reproducing lead times.
 */
@Serializable
data class G0FullLeadTimeSweepMutantStats(
    val mutantId: Int,
    val totalTicks: Int,
    val neverReproducedCount: Int,
    val nonMonotonicCount: Int,
    val minLeadTimeHistogram: Map<Double, Int>,
    val maxLeadTimeHistogram: Map<Double, Int>,
    val minLeadTimePercentiles: Map<String, Double>,
    val maxLeadTimePercentiles: Map<String, Double>,
)

/**
 * Aggregate summary across an entire `G0FullLeadTimeSweepAnalysis` run, built by reading back every
 * worker's streamed [TickG0FullSweepResult] detail file.
 *
 * @property runId Evaluation run id the analysis was restricted to, or `null` if it covered every
 *   run.
 * @property totalTicksAnalyzed Number of flagged ticks the analysis swept.
 * @property neverReproducedCount See [G0FullLeadTimeSweepMutantStats.neverReproducedCount], across
 *   every swept tick.
 * @property nonMonotonicCount See [G0FullLeadTimeSweepMutantStats.nonMonotonicCount], across every
 *   swept tick — the headline count for "does lead time help non-monotonically."
 * @property nonMonotonicTickIds Ids of the [nonMonotonicCount] ticks, for direct inspection (e.g.
 *   re-running [tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis]
 *   against just these ids to look at their full [TickG0FullSweepResult.steps] sequence).
 * @property minLeadTimeHistogram [TickG0FullSweepResult.minReproducingLeadTimeSeconds] grouped by
 *   exact value to tick count, over every tick that reproduces at all.
 * @property maxLeadTimeHistogram [TickG0FullSweepResult.maxReproducingLeadTimeSeconds] grouped by
 *   exact value to tick count, over every tick that reproduces at all.
 * @property minLeadTimePercentiles Nearest-rank percentiles ("p50", "p75", "p90", "p95", "p99")
 *   over every minimum reproducing lead time.
 * @property maxLeadTimePercentiles Nearest-rank percentiles ("p50", "p75", "p90", "p95", "p99")
 *   over every maximum reproducing lead time.
 * @property mutantStats Per-mutant breakdown — see [G0FullLeadTimeSweepMutantStats] — one entry per
 *   distinct original mutant actually encountered among the swept ticks.
 */
@Serializable
data class G0FullLeadTimeSweepSummary(
    val runId: Int?,
    val totalTicksAnalyzed: Int,
    val neverReproducedCount: Int,
    val nonMonotonicCount: Int,
    val nonMonotonicTickIds: List<Long>,
    val minLeadTimeHistogram: Map<Double, Int>,
    val maxLeadTimeHistogram: Map<Double, Int>,
    val minLeadTimePercentiles: Map<String, Double>,
    val maxLeadTimePercentiles: Map<String, Double>,
    val mutantStats: List<G0FullLeadTimeSweepMutantStats>,
)
