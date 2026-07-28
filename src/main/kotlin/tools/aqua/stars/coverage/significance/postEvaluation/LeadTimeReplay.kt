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

package tools.aqua.stars.coverage.significance.postEvaluation

import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository

/**
 * Shared support for "lead time" replays (used by both `TickReplayAnalysis` and
 * `G0MutantCoverageReplayAnalysis`): instead of reconstructing a recorded tick right at its
 * critical moment and giving a mutant exactly one step to react, reconstruct an *earlier* tick of
 * the same run and step forward through to (and one past) the original moment — see
 * [tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector.replayFromTickForDuration]'s docs
 * for why a single-step replay from the critical moment itself can under-report what a mutant (or
 * the background traffic around it) would actually do given real lead time.
 */
object LeadTimeReplay {

  /**
   * Simulation step length, matching
   * [tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector]'s default.
   */
  private const val STEP_LENGTH_MILLIS = 100L

  /**
   * All ticks of the same (run, scenario, mutant) as [originalTick] — the candidate pool
   * [findStartTick] picks the closest-to-target one from. Cached per (scenarioConfigId, mutantId)
   * by the caller across a batch of [originalTick]s that often share a scenario/mutant, since this
   * is a full DB round trip.
   */
  fun candidatesFor(originalTick: MetricFailedMonitorsEntry): List<MetricFailedMonitorsEntry> =
      MetricFailedMonitorsRepository.getAllForScenarioAndMutant(
          runId = originalTick.runId,
          scenarioConfigId = originalTick.scenarioConfigId,
          mutantId = originalTick.mutantId)

  /**
   * The tick among [candidates] closest to [leadTimeSeconds] before [originalTick] — the "closest
   * available" starting point for a lead-time replay. Falls back to [originalTick] itself if
   * [candidates] is empty (shouldn't happen: [originalTick] is always its own candidate) or ties
   * land on it (e.g. [leadTimeSeconds] is smaller than the gap between recorded ticks).
   */
  fun findStartTick(
      originalTick: MetricFailedMonitorsEntry,
      leadTimeSeconds: Double,
      candidates: List<MetricFailedMonitorsEntry>,
  ): MetricFailedMonitorsEntry {
    val targetMillis = originalTick.tick - (leadTimeSeconds * 1000).toLong()
    return candidates.minByOrNull { kotlin.math.abs(it.tick - targetMillis) } ?: originalTick
  }

  /**
   * Number of simulation steps to run from [startTick] to land one step *past* [originalTick] —
   * "the next tick which should induce the accident" per the original single-step semantics, now
   * preceded by however many steps of real lead time [startTick] actually is before it. Based on
   * the actual recorded gap between the two ticks (not the nominal lead time), since
   * [findStartTick] only finds the *closest available* tick, which may not land exactly on the
   * requested offset.
   */
  fun stepCountThroughOriginal(
      originalTick: MetricFailedMonitorsEntry,
      startTick: MetricFailedMonitorsEntry,
  ): Int {
    val gapMillis = (originalTick.tick - startTick.tick).coerceAtLeast(0)
    return (gapMillis / STEP_LENGTH_MILLIS).toInt() + 1
  }

  /** Folder-name-safe rendering of a lead time in seconds, e.g. 0.5 -> "0.5s". */
  fun folderSuffix(leadTimeSeconds: Double): String = "${leadTimeSeconds}s"
}
