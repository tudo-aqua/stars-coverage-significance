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
 * Where a sampled tick came from, in
 * `tools.aqua.stars.coverage.significance.postEvaluation.ReplayDeterminismAnalysis`.
 */
@Serializable
enum class SampleSource {
  /** Drawn via a fixed-seed random sample, up to `--ticksPerMutant` per mutant. */
  RANDOM,
  /**
   * Drawn from the existing `G0FullLeadTimeSweepSummary.nonMonotonicTickIds` (already-known
   * non-monotonic ticks), up to `--maxNonMonotonicSample`.
   */
  NON_MONOTONIC_TARGETED,
}

/**
 * Classification of one double-replay attempt (see [DeterminismReplayAttemptResult]), checked in
 * this precedence order: an exception in either run always wins, then a step-count mismatch between
 * the two runs, then "both runs returned nothing", then raw vehicle-data/derived-outcome
 * mismatches, then "fewer steps than requested (but the two runs still agreed)", else success.
 */
@Serializable
enum class ReplayAttemptOutcome {
  /** Both runs agreed, at every step, within epsilon, including the derived G0 outcome. */
  SUCCESS,
  /**
   * Vehicle-id set or position/speed/accel/lane/type mismatch beyond epsilon between the two runs.
   */
  NONDETERMINISTIC_VEHICLE_DATA,
  /**
   * Raw data agreed (within epsilon) but `g0Accidents.holds(...)` differed between the two runs.
   */
  NONDETERMINISTIC_G0_OUTCOME,
  /** The two runs returned a different number of steps. */
  NONDETERMINISTIC_STEP_COUNT,
  /** Both runs agreed, but returned fewer steps than requested (the ego left mid-loop). */
  TRUNCATED_MID_LOOP,
  /** Both runs agreed, and both returned an empty list (the ego wasn't present after placement). */
  INCONCLUSIVE_EMPTY_AT_PLACEMENT,
  /** Either run threw. */
  EXCEPTION,
}

/**
 * @property exceptionClass Fully qualified (or simple, if unavailable) class name of the thrown
 *   exception.
 * @property message [Throwable.message] of the thrown exception.
 * @property stackTraceSnippet The first ~10 frames of [Throwable.stackTraceToString], joined by
 *   `"\n"` — enough to identify the throw site without ballooning the detail file.
 */
@Serializable
data class ExceptionCapture(
    val exceptionClass: String,
    val message: String?,
    val stackTraceSnippet: String,
)

/**
 * Vehicles present in one run's step but not the other's, at [stepIndex].
 *
 * @property stepIndex Index into the replayed step list this mismatch was found at.
 * @property onlyInRunA Vehicle ids present in run A's step but not run B's.
 * @property onlyInRunB Vehicle ids present in run B's step but not run A's.
 */
@Serializable
data class VehicleSetMismatch(
    val stepIndex: Int,
    val onlyInRunA: List<String>,
    val onlyInRunB: List<String>,
)

/**
 * @property stepIndex Index into the replayed step list this mismatch was found at.
 * @property vehicleId Id of the vehicle this mismatch was found on.
 * @property field One of `"positionOnLaneMeters"`, `"speedMetersPerSecond"`,
 *   `"accelerationMetersPerSecondSquared"`, `"laneIndex"`, `"vehicleTypeId"`.
 * @property valueA [field]'s value in run A, stringified.
 * @property valueB [field]'s value in run B, stringified.
 * @property absDifference `null` for the two discrete fields (`laneIndex`, `vehicleTypeId`), which
 *   have no meaningful numeric distance.
 */
@Serializable
data class VehicleFieldMismatch(
    val stepIndex: Int,
    val vehicleId: String,
    val field: String,
    val valueA: String,
    val valueB: String,
    val absDifference: Double? = null,
)

/**
 * The derived `g0Accidents.holds(...)` result disagreeing between the two runs at [stepIndex].
 *
 * @property stepIndex Index into the replayed step list this mismatch was found at.
 * @property g0FailedA Run A's `g0Accidents.holds(...)` result at this step.
 * @property g0FailedB Run B's `g0Accidents.holds(...)` result at this step.
 */
@Serializable
data class G0OutcomeMismatch(
    val stepIndex: Int,
    val g0FailedA: Boolean,
    val g0FailedB: Boolean,
)

/**
 * One (tick, lead-time) double-replay attempt:
 * `tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector.replayFromTickForDuration` called
 * twice with identical arguments, diffed at the raw per-vehicle level and the derived
 * `tools.aqua.stars.coverage.significance.tsc.g0Accidents` level, and classified into
 * [ReplayAttemptOutcome].
 *
 * @property tickId `metric_failed_monitors.id` of the analyzed tick.
 * @property originalTick `metric_failed_monitors.tick` value of the analyzed tick.
 * @property runId Evaluation run the analyzed tick belongs to.
 * @property scenarioConfigId Scenario starting configuration the analyzed tick belongs to.
 * @property mutantId Id of the mutant that originally produced this tick.
 * @property sampleSource How this tick was chosen for the sample.
 * @property leadTimeSeconds How far before the original tick this attempt reconstructed the scene
 *   from.
 * @property startTickId `metric_failed_monitors.id` of the recorded tick the scene was
 *   reconstructed from for this attempt.
 * @property requestedStepCount Number of simulation steps requested from [startTickId].
 * @property outcome This attempt's [ReplayAttemptOutcome] classification.
 * @property actualStepCountA Number of `TimeStep`s run A actually returned, or `null` if run A
 *   threw.
 * @property actualStepCountB Same, for run B.
 * @property exceptionA Captured exception from run A, if it threw.
 * @property exceptionB Captured exception from run B, if it threw.
 * @property vehicleSetMismatches Steps where the two runs' vehicle-id sets differed.
 * @property vehicleFieldMismatches Per-vehicle field mismatches beyond epsilon.
 * @property g0OutcomeMismatches Steps where the derived `g0Accidents.holds(...)` result differed.
 * @property maxAbsDiffPositionMeters Largest absolute per-vehicle position difference observed
 *   across every matched vehicle/step of this attempt — recorded even when every field stayed under
 *   epsilon, so the aggregate report can empirically show how much headroom the chosen epsilon
 *   actually has. `null` when no step could be diffed (e.g. [ReplayAttemptOutcome.EXCEPTION]).
 * @property maxAbsDiffSpeedMps Same, for speed.
 * @property maxAbsDiffAccelMps2 Same, for acceleration.
 */
@Serializable
data class DeterminismReplayAttemptResult(
    val tickId: Long,
    val originalTick: Long,
    val runId: Int,
    val scenarioConfigId: Int,
    val mutantId: Int,
    val sampleSource: SampleSource,
    val leadTimeSeconds: Double,
    val startTickId: Long,
    val requestedStepCount: Int,
    val outcome: ReplayAttemptOutcome,
    val actualStepCountA: Int?,
    val actualStepCountB: Int?,
    val exceptionA: ExceptionCapture? = null,
    val exceptionB: ExceptionCapture? = null,
    val vehicleSetMismatches: List<VehicleSetMismatch> = emptyList(),
    val vehicleFieldMismatches: List<VehicleFieldMismatch> = emptyList(),
    val g0OutcomeMismatches: List<G0OutcomeMismatch> = emptyList(),
    val maxAbsDiffPositionMeters: Double? = null,
    val maxAbsDiffSpeedMps: Double? = null,
    val maxAbsDiffAccelMps2: Double? = null,
)

/**
 * Max/p99/p50 of an observed absolute-difference distribution, over `SUCCESS` attempts only.
 *
 * @property maxAbsDiff Largest observed absolute difference.
 * @property p99AbsDiff 99th-percentile observed absolute difference.
 * @property p50AbsDiff Median observed absolute difference.
 */
@Serializable
data class EpsilonHeadroomStats(
    val maxAbsDiff: Double,
    val p99AbsDiff: Double,
    val p50AbsDiff: Double,
)

/**
 * Per-mutant breakdown across an entire `ReplayDeterminismAnalysis` run.
 *
 * @property mutantId Unique identifier of the mutant (`mutants.id`) every one of these attempts
 *   originally produced its tick as.
 * @property totalAttempts Total number of double-replay attempts for this mutant.
 * @property outcomeCounts [ReplayAttemptOutcome] counts across this mutant's attempts.
 * @property leadTimeBucketOutcomeCounts [outcomeCounts], split by lead-time bucket.
 */
@Serializable
data class ReplayDeterminismMutantStats(
    val mutantId: Int,
    val totalAttempts: Int,
    val outcomeCounts: Map<ReplayAttemptOutcome, Int>,
    val leadTimeBucketOutcomeCounts: Map<String, Map<ReplayAttemptOutcome, Int>>,
)

/**
 * Aggregate summary across an entire `ReplayDeterminismAnalysis` run, built by reading back every
 * worker's streamed [DeterminismReplayAttemptResult] detail file.
 *
 * @property runId Evaluation run id the analysis was restricted to, or `null` if it covered every
 *   run.
 * @property totalAttempts Total number of (tick, lead-time) double-replay attempts.
 * @property totalTicksSampled Number of distinct ticks sampled.
 * @property mutantsSampled Number of distinct mutants represented in the sample.
 * @property outcomeCounts [ReplayAttemptOutcome] counts across every attempt.
 * @property outcomeCountsBySampleSource [outcomeCounts], split by [SampleSource].
 * @property leadTimeBucketOutcomeCounts [outcomeCounts], split by lead-time bucket (see
 *   `LeadTimeBuckets`).
 * @property mutantStats Per-mutant breakdown — see [ReplayDeterminismMutantStats].
 * @property exampleTickIdsByOutcome Up to 10 example tick ids per non-`SUCCESS` outcome.
 * @property epsilonHeadroom Keyed by `"positionMeters"`, `"speedMps"`, `"accelMps2"`.
 */
@Serializable
data class ReplayDeterminismSummary(
    val runId: Int?,
    val totalAttempts: Int,
    val totalTicksSampled: Int,
    val mutantsSampled: Int,
    val outcomeCounts: Map<ReplayAttemptOutcome, Int>,
    val outcomeCountsBySampleSource: Map<SampleSource, Map<ReplayAttemptOutcome, Int>>,
    val leadTimeBucketOutcomeCounts: Map<String, Map<ReplayAttemptOutcome, Int>>,
    val mutantStats: List<ReplayDeterminismMutantStats>,
    val exampleTickIdsByOutcome: Map<ReplayAttemptOutcome, List<Long>>,
    val epsilonHeadroom: Map<String, EpsilonHeadroomStats>,
)
