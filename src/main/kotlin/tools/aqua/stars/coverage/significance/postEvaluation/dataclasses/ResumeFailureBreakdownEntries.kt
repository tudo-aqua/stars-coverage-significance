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
 * Reproducing/diverged/inconclusive breakdown over some slice of lead-time sweep steps, re-derived
 * from the existing `G0FullLeadTimeSweepAnalysis` detail files by
 * `tools.aqua.stars.coverage.significance.postEvaluation.ResumeFailureAggregation`.
 *
 * @property totalSteps Total number of (non-final) lead-time sweep steps in this slice.
 * @property reproducingCount Of [totalSteps], how many had `g0Failed == true`.
 * @property divergedCount Of [totalSteps], how many had `g0Failed == false`.
 * @property inconclusiveCount Of [totalSteps], how many had `g0Failed == null`.
 * @property inconclusiveFraction [inconclusiveCount] / [totalSteps] — directly answers "how often
 *   is resuming from a later tick simply not possible" (the ego wasn't present right after
 *   placement).
 * @property noRealCandidateNearTargetCount Of [totalSteps], how many had the actual recorded gap to
 *   the chosen start tick more than 0.05s away from the requested lead time — i.e.
 *   `LeadTimeReplay.findStartTick` had no real candidate near the target and fell back to whatever
 *   was closest.
 * @property noRealCandidateNearTargetFraction [noRealCandidateNearTargetCount] / [totalSteps].
 */
@Serializable
data class ResumeOutcomeBreakdown(
    val totalSteps: Int,
    val reproducingCount: Int,
    val divergedCount: Int,
    val inconclusiveCount: Int,
    val inconclusiveFraction: Double,
    val noRealCandidateNearTargetCount: Int,
    val noRealCandidateNearTargetFraction: Double,
)

/**
 * Aggregate summary across the existing `G0FullLeadTimeSweepAnalysis` detail files, re-read
 * read-only — no new simulation.
 *
 * @property runId Evaluation run id the existing sweep was restricted to, or `null` for every run.
 * @property totalTicksInDetailFiles Number of distinct ticks found in the existing detail files.
 * @property totalTicksFlaggedInDb `null` unless `--checkDbCounts` was requested.
 * @property ticksMissingFromDetailFiles `totalTicksFlaggedInDb - totalTicksInDetailFiles`, `null`
 *   under the same condition. A nonzero value can't by itself distinguish an already-logged
 *   "unknown scenario" skip from a crashed-worker data loss in the original sweep.
 * @property overall Breakdown across every analyzed step.
 * @property byMutant [overall], split by `originalMutantId`.
 * @property byLeadTimeBucket [overall], split by lead-time bucket.
 * @property byMutantAndLeadTimeBucket [overall], split by both mutant and lead-time bucket.
 */
@Serializable
data class ResumeFailureSummary(
    val runId: Int?,
    val totalTicksInDetailFiles: Int,
    val totalTicksFlaggedInDb: Int? = null,
    val ticksMissingFromDetailFiles: Int? = null,
    val overall: ResumeOutcomeBreakdown,
    val byMutant: Map<Int, ResumeOutcomeBreakdown>,
    val byLeadTimeBucket: Map<String, ResumeOutcomeBreakdown>,
    val byMutantAndLeadTimeBucket: Map<Int, Map<String, ResumeOutcomeBreakdown>>,
)
