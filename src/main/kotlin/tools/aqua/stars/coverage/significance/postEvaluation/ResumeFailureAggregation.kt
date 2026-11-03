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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.forEachLine
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.streams.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ResumeFailureSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ResumeOutcomeBreakdown
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickG0FullSweepResult
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration

/**
 * Re-aggregates the EXISTING [G0FullLeadTimeSweepAnalysis] detail files — read-only, no new
 * simulation — to answer "how often is resuming from a later tick inconclusive/diverged", broken
 * down by mutant and lead-time bucket. [G0FullLeadTimeSweepAnalysis.aggregate]'s published summary
 * already has this data per-tick (`TickG0FullSweepResult.steps`) but never rolls it up this way.
 *
 * Also derives, purely from already-stored fields (no DB access), how often
 * `LeadTimeReplay.findStartTick` had no real candidate near the requested lead time and fell back
 * to whatever recorded tick was closest: `actualLeadTimeSeconds = (step.stepCount - 1) / 10.0` (the
 * real gap `stepCountThroughOriginal` derived the replay from) differing from
 * `step.leadTimeSeconds` (the nominal lead time requested) by more than half a nominal 0.1s step
 * means the candidate pool simply had nothing close enough at that point in that trajectory.
 */
object ResumeFailureAggregation {

  // Half of the nominal 0.1s sweep step - a gap below this is "close enough", above it means
  // findStartTick picked a candidate that wasn't actually near the requested lead time.
  private const val NO_CANDIDATE_THRESHOLD_SECONDS = 0.05

  // Points at the EXISTING sweep's own folder (read-only). Duplicated path logic rather than
  // widening G0FullLeadTimeSweepAnalysis's private helpers' visibility, to keep this change
  // additive-only.
  private fun existingSweepDetailDir(): Path =
      Path.of(POST_EVALUATION_BASE_DIR, "g0_full_lead_time_sweep", "details")

  private fun discoverExistingDetailFiles(runId: Int?): List<Path> {
    val prefix = "g0_full_lead_time_sweep_${runId?.toString() ?: "all"}_worker"
    val dir = existingSweepDetailDir()
    if (!dir.exists()) return emptyList()
    return Files.list(dir).use { stream ->
      stream
          .filter { path ->
            val name = path.fileName.toString()
            name.startsWith(prefix) && name.endsWith(".jsonl")
          }
          .sorted()
          .toList()
    }
  }

  private fun basePath(): Path = Path.of(POST_EVALUATION_BASE_DIR, "replay_diagnostics")

  private class Accumulator {
    var totalSteps = 0
    var reproducing = 0
    var diverged = 0
    var inconclusive = 0
    var noRealCandidate = 0

    fun toBreakdown(): ResumeOutcomeBreakdown =
        ResumeOutcomeBreakdown(
            totalSteps = totalSteps,
            reproducingCount = reproducing,
            divergedCount = diverged,
            inconclusiveCount = inconclusive,
            inconclusiveFraction =
                if (totalSteps == 0) 0.0 else inconclusive.toDouble() / totalSteps,
            noRealCandidateNearTargetCount = noRealCandidate,
            noRealCandidateNearTargetFraction =
                if (totalSteps == 0) 0.0 else noRealCandidate.toDouble() / totalSteps,
        )
  }

  /**
   * @param runId Evaluation run id the existing sweep was restricted to, or `null` for every run.
   * @param checkDbCounts Whether to additionally compare the existing detail files' total distinct
   *   tick count against a fresh `MetricFailedMonitorsRepository.getAllWithNextTickG0Failed(runId)`
   *   count — needs a live DB connection; `false` by default to keep the common path DB-free.
   * @return The written [ResumeFailureSummary].
   */
  fun aggregate(runId: Int?, checkDbCounts: Boolean = false): ResumeFailureSummary {
    val overall = Accumulator()
    val byMutant = mutableMapOf<Int, Accumulator>()
    val byBucket = mutableMapOf<String, Accumulator>()
    val byMutantBucket = mutableMapOf<Int, MutableMap<String, Accumulator>>()
    val tickIdsSeen = mutableSetOf<Long>()

    val detailFiles = discoverExistingDetailFiles(runId)
    println(
        "Re-aggregating ${detailFiles.size} existing G0FullLeadTimeSweepAnalysis detail file(s) " +
            "for runId=${runId ?: "all"} (read-only, no new simulation).")

    for (path in detailFiles) {
      path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val tick = jsonConfiguration.decodeFromString<TickG0FullSweepResult>(line)
        tickIdsSeen += tick.tickId
        val mutantAcc = byMutant.getOrPut(tick.originalMutantId) { Accumulator() }

        // Exclude the final (guaranteed full-scenario-replay) step - same convention
        // G0FullLeadTimeSweepAnalysis.deriveStats uses, so these numbers are comparable to the
        // published neverReproducedCount/nonMonotonicCount.
        val analyzedSteps = if (tick.steps.size > 1) tick.steps.dropLast(1) else tick.steps

        for (step in analyzedSteps) {
          val bucket = LeadTimeBuckets.bucketLabel(step.leadTimeSeconds)
          val bucketAcc = byBucket.getOrPut(bucket) { Accumulator() }
          val mutantBucketAcc =
              byMutantBucket
                  .getOrPut(tick.originalMutantId) { mutableMapOf() }
                  .getOrPut(bucket) { Accumulator() }

          val actualLeadTimeSeconds = (step.stepCount - 1) / 10.0
          val noRealCandidate =
              abs(actualLeadTimeSeconds - step.leadTimeSeconds) > NO_CANDIDATE_THRESHOLD_SECONDS

          for (acc in listOf(overall, mutantAcc, bucketAcc, mutantBucketAcc)) {
            acc.totalSteps++
            when (step.g0Failed) {
              true -> acc.reproducing++
              false -> acc.diverged++
              null -> acc.inconclusive++
            }
            if (noRealCandidate) acc.noRealCandidate++
          }
        }
      }
    }

    var totalTicksFlaggedInDb: Int? = null
    var ticksMissingFromDetailFiles: Int? = null
    if (checkDbCounts) {
      val dbCount = MetricFailedMonitorsRepository.getAllWithNextTickG0Failed(runId).size
      totalTicksFlaggedInDb = dbCount
      ticksMissingFromDetailFiles = dbCount - tickIdsSeen.size
    }

    val summary =
        ResumeFailureSummary(
            runId = runId,
            totalTicksInDetailFiles = tickIdsSeen.size,
            totalTicksFlaggedInDb = totalTicksFlaggedInDb,
            ticksMissingFromDetailFiles = ticksMissingFromDetailFiles,
            overall = overall.toBreakdown(),
            byMutant = byMutant.mapValues { it.value.toBreakdown() },
            byLeadTimeBucket = byBucket.mapValues { it.value.toBreakdown() },
            byMutantAndLeadTimeBucket =
                byMutantBucket.mapValues { (_, m) -> m.mapValues { it.value.toBreakdown() } },
        )

    val base = basePath()
    Files.createDirectories(base)
    val summaryPath = base.resolve("resume_failure_breakdown_${runId?.toString() ?: "all"}.json")
    summaryPath.writeText(jsonConfiguration.encodeToString(summary))
    println("Finished ResumeFailureAggregation. Summary written to: $summaryPath")
    return summary
  }
}
