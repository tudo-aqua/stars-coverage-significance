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
 * Per-mutant aggregate across an entire `G0MutantCoverageReplayAnalysis` run.
 *
 * @property mutantId Unique identifier of the mutant.
 * @property mutantNumber Mutant number (see `mutants.mutant_number`).
 * @property className Mutant class name.
 * @property originalTickCount Number of analyzed ticks originally produced by this mutant (i.e.
 *   this mutant is `metric_failed_monitors.mutant_id` for that row).
 * @property originalTickReproducedCount Of [originalTickCount], how many reproduced the recorded G0
 *   failure when this mutant was replayed against its own tick again.
 * @property newKillTickIds Ids of ticks *not* originally produced by this mutant, where
 *   substituting this mutant into the recorded scene also triggered a G0 failure — i.e. kills this
 *   mutant wasn't originally credited with.
 */
@Serializable
data class MutantG0ReplayStats(
    val mutantId: Int,
    val mutantNumber: Int,
    val className: String,
    val originalTickCount: Int,
    val originalTickReproducedCount: Int,
    val newKillTickIds: List<Int>,
)

/**
 * Aggregate summary across an entire `G0MutantCoverageReplayAnalysis` run, built by reading back
 * every worker's streamed [TickG0ReplaySummary] detail file.
 *
 * @property runId Evaluation run id the analysis was restricted to, or `null` if it covered every
 *   run.
 * @property totalTicksAnalyzed Number of flagged ticks the analysis replayed.
 * @property totalMutants Number of known mutants each tick was replayed against.
 * @property originalMutantReproducedCount Ticks where replaying the original mutant reproduced the
 *   recorded G0 failure.
 * @property originalMutantNotReproducedCount Ticks where replaying the original mutant did *not*
 *   reproduce the failure — a discrepancy between the recorded run and the replay worth
 *   investigating (see
 *   [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis] docs on
 *   replay fidelity).
 * @property originalMutantInconclusiveCount Ticks where the original mutant's replay was
 *   inconclusive (the ego left the simulation before a next tick could be evaluated).
 * @property unavoidableTickCount Ticks where *every other* mutant, substituted into the exact same
 *   recorded scene, also triggered a G0 failure — i.e. no known mutant strategy avoids it.
 * @property unavoidableTickIds Ids of the [unavoidableTickCount] ticks.
 * @property mutantsWithNewKillsCount Number of distinct mutants with at least one entry in their
 *   [MutantG0ReplayStats.newKillTickIds].
 * @property mutantStats Per-mutant breakdown, one entry per known mutant.
 */
@Serializable
data class G0MutantCoverageReplaySummary(
    val runId: Int?,
    val totalTicksAnalyzed: Int,
    val totalMutants: Int,
    val originalMutantReproducedCount: Int,
    val originalMutantNotReproducedCount: Int,
    val originalMutantInconclusiveCount: Int,
    val unavoidableTickCount: Int,
    val unavoidableTickIds: List<Int>,
    val mutantsWithNewKillsCount: Int,
    val mutantStats: List<MutantG0ReplayStats>,
)
