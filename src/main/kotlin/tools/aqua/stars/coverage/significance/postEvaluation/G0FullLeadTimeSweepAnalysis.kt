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
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists
import kotlin.io.path.forEachLine
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.streams.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0FullLeadTimeSweepMutantStats
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0FullLeadTimeSweepSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.LeadTimeSweepStepResult
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickG0FullSweepResult
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * For every recorded tick (`metric_failed_monitors` row) whose *next* tick was flagged as a G0
 * (Accidents) failure during the original evaluation run, replays that exact recorded scene's own
 * original mutant (`tick.mutantId`) repeatedly, stepping the reconstruction *backward* in the
 * simulation's native 0.1s step length (0.0, 0.1, 0.2, ...) all the way to the start of the
 * recorded scenario, via [LeadTimeReplay] +
 * `LibsumoDynamicDataCollector.replayFromTickForDuration`.
 *
 * This differs from
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis] in one
 * crucial way: that analysis stops at the *first* lead time that fails to reproduce the recorded
 * failure, implicitly assuming reproduction decays monotonically as lead time grows. But the
 * autopilot under test (and the mutants substituted for it) are state-based, and so is the
 * surrounding traffic's behavior — a different (earlier) starting point is a genuinely different
 * trajectory, not "more of the same, with extra steps prepended," so there's no a priori reason
 * reproduction can't turn back on at an even earlier lead time after failing to reproduce at a
 * nearer one. This analysis sweeps every lead time unconditionally to find:
 * 1. The *minimum* and *maximum* lead time at which the original mutant still reproduces the
 *    recorded failure ([TickG0FullSweepResult.minReproducingLeadTimeSeconds] /
 *    [TickG0FullSweepResult.maxReproducingLeadTimeSeconds]) — not just the first point of
 *    departure.
 * 2. Whether reproduction is actually monotonic in lead time at all
 *    ([TickG0FullSweepResult.isMonotonic]) — direct evidence for or against the assumption the
 *    other analysis makes.
 *
 * Because it never stops early, this analysis does strictly more simulation work per tick than
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0DivergenceLeadTimeAnalysis] — every tick
 * is swept all the way back to its scenario's start, where that analysis often stops after just a
 * few steps. Expect a correspondingly longer total run time.
 *
 * Shares the same `g0Accidents.holds(nextTick)` reasoning (safe without the full `TSCEvaluation`
 * framework) and one-process-per-core parallelism rationale as
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis] — see its
 * docs for both. [runWorkerSlice] is the per-process entry point, deterministically claiming every
 * Nth flagged tick; [aggregate] reads every worker's streamed NDJSON detail file back and writes
 * one summary JSON.
 */
object G0FullLeadTimeSweepAnalysis {

  private fun basePath(): Path = Path.of(POST_EVALUATION_BASE_DIR, "g0_full_lead_time_sweep")

  private fun detailDir(): Path = basePath().resolve("details")

  /** Detail (NDJSON) file path for one worker's share of ticks. */
  private fun detailFilePath(runId: Int?, workerId: Int): Path =
      detailDir()
          .resolve("g0_full_lead_time_sweep_${runId?.toString() ?: "all"}_worker$workerId.jsonl")

  /**
   * Worker entry point: sweeps this worker's deterministic share of flagged ticks (every tick at
   * index `i` where `i % numWorkers == workerId`, ticks ordered by id) all the way backward to
   * their scenario's start against each tick's own original mutant, streaming one
   * [TickG0FullSweepResult] per line to this worker's own detail file as each tick completes.
   *
   * @param runId Evaluation run id to restrict to, or `null` to include every run's flagged ticks.
   * @param workerId This worker's index, in `0 until numWorkers`.
   * @param numWorkers Total number of workers splitting the flagged-tick list.
   */
  fun runWorkerSlice(runId: Int?, workerId: Int, numWorkers: Int) {
    val allTicks =
        MetricFailedMonitorsRepository.getAllWithNextTickG0Failed(runId).sortedBy { it.id }
    val myTicks = allTicks.filterIndexed { index, _ -> index % numWorkers == workerId }
    println(
        "[worker-$workerId] Sweeping ${myTicks.size}/${allTicks.size} flagged ticks" +
            (runId?.let { " for runId=$it" } ?: " across all runs") +
            ".")

    sweepTicks(ticks = myTicks, runId = runId, workerId = workerId)
  }

  /**
   * Runs the full backward lead-time sweep for every tick in [ticks] against its own original
   * mutant, streaming one [TickG0FullSweepResult] per line to this worker's detail file as each
   * tick completes.
   *
   * Holds the actual sweep work factored out of [runWorkerSlice] so it can also be driven manually
   * — e.g. to re-sweep just the ticks [G0FullLeadTimeSweepSummary.nonMonotonicTickIds] flagged for
   * closer inspection — without going through the `numWorkers`/`workerId` slicing [runWorkerSlice]
   * uses to split the full set of flagged ticks across worker processes.
   *
   * @param ticks The ticks to sweep, in order.
   * @param runId Evaluation run id the ticks were restricted to, or `null` for every run — only
   *   used to name the detail file, must match how [ticks] was queried.
   * @param workerId Identifies the detail file this call writes/appends to, and is used in log
   *   output — for a manual, non-worker call, any id not colliding with a real worker's is fine.
   */
  fun sweepTicks(ticks: List<MetricFailedMonitorsEntry>, runId: Int?, workerId: Int) {
    val collector = LibsumoDynamicDataCollector()
    // Many flagged ticks in a row often share a scenario/mutant (an accident tends to be flagged
    // over several consecutive ticks) - avoid re-querying the same candidate pool for each.
    val candidatesCache = mutableMapOf<Pair<Int, Int>, List<MetricFailedMonitorsEntry>>()

    Files.createDirectories(detailDir())
    detailFilePath(runId, workerId).bufferedWriter().use { writer ->
      for (tick in ticks) {
        val tickId = checkNotNull(tick.id)
        val scenario = ScenarioStartingConfigurationRepository.getById(tick.scenarioConfigId)
        if (scenario == null) {
          println(
              "[worker-$workerId] Tick $tickId references unknown scenario " +
                  "${tick.scenarioConfigId} — skipping.")
          continue
        }

        val candidates =
            candidatesCache.getOrPut(tick.scenarioConfigId to tick.mutantId) {
              LeadTimeReplay.candidatesFor(tick)
            }

        val steps = mutableListOf<LeadTimeSweepStepResult>()
        var previousStartTickId: Long? = null
        // Integer tenths of a second, to step in exact 0.1s increments without floating-point
        // drift from repeatedly adding 0.1.
        var tenths = 0

        // Unlike G0DivergenceLeadTimeAnalysis, this loop never stops early on a diverged or
        // inconclusive step - only once there's no earlier recorded tick left to step back to.
        while (true) {
          val leadTimeSeconds = tenths / 10.0
          val startTick = LeadTimeReplay.findStartTick(tick, leadTimeSeconds, candidates)
          val startTickId = checkNotNull(startTick.id)
          if (previousStartTickId != null && startTickId == previousStartTickId) break
          previousStartTickId = startTickId

          val stepCount = LeadTimeReplay.stepCountThroughOriginal(tick, startTick)
          val replaySteps =
              collector.replayFromTickForDuration(
                  tick.runId, startTick, scenario, tick.mutantId, stepCount)
          // null (inconclusive) rather than stopping the sweep: this specific starting point left
          // the ego stranded, but a different (earlier) one is a different trajectory and may not.
          val g0Failed =
              if (replaySteps.isEmpty()) null else replaySteps.any { !g0Accidents.holds(it) }
          steps += LeadTimeSweepStepResult(leadTimeSeconds, startTickId, stepCount, g0Failed)
          tenths++
        }

        val reproducingSteps = steps.filter { it.g0Failed == true }
        val divergedSteps = steps.filter { it.g0Failed == false }
        val inconclusiveSteps = steps.filter { it.g0Failed == null }
        val minReproducing = reproducingSteps.minOfOrNull { it.leadTimeSeconds }
        val maxReproducing = reproducingSteps.maxOfOrNull { it.leadTimeSeconds }
        val isMonotonic = isMonotonicallyReproducing(steps)

        println(
            "[worker-$workerId] Tick $tickId (tick=${tick.tick}, run=${tick.runId}, " +
                "mutant=${tick.mutantId}): ${steps.size} lead-time step(s) swept, " +
                "reproducing=${reproducingSteps.size} diverged=${divergedSteps.size} " +
                "inconclusive=${inconclusiveSteps.size} isMonotonic=$isMonotonic" +
                (minReproducing?.let { ", min=$it" } ?: "") +
                (maxReproducing?.let { ", max=$it" } ?: "") +
                ".")

        val result =
            TickG0FullSweepResult(
                tickId = tickId,
                originalTick = tick.tick,
                runId = tick.runId,
                scenarioConfigId = tick.scenarioConfigId,
                originalMutantId = tick.mutantId,
                steps = steps,
                minReproducingLeadTimeSeconds = minReproducing,
                maxReproducingLeadTimeSeconds = maxReproducing,
                reproducingCount = reproducingSteps.size,
                divergedCount = divergedSteps.size,
                inconclusiveCount = inconclusiveSteps.size,
                isMonotonic = isMonotonic,
            )
        writer.write(jsonConfiguration.encodeToString(result))
        writer.newLine()
        writer.flush()
      }
    }
    println("[worker-$workerId] Finished. Detail written to: " + detailFilePath(runId, workerId))
  }

  /**
   * `false` iff, scanning [steps] in increasing lead-time order (ignoring `g0Failed == null`
   * steps), a reproducing (`true`) step is ever found after a diverged (`false`) one — i.e.
   * reproduction "turned back on" at a larger lead time after turning off at a smaller one.
   * Vacuously `true` if every non-null step agrees (including when there are none, or only one
   * kind).
   */
  private fun isMonotonicallyReproducing(steps: List<LeadTimeSweepStepResult>): Boolean {
    var seenDiverged = false
    for (step in steps) {
      when (step.g0Failed) {
        true -> if (seenDiverged) return false
        false -> seenDiverged = true
        null -> Unit
      }
    }
    return true
  }

  /**
   * Finds every worker detail file for [runId] currently on disk, by directory listing rather than
   * an assumed `0 until numWorkers` range — same rationale as
   * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis]'s
   * `discoverDetailFiles`.
   */
  private fun discoverDetailFiles(runId: Int?): List<Path> {
    val prefix = "g0_full_lead_time_sweep_${runId?.toString() ?: "all"}_worker"
    val dir = detailDir()
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

  /** Nearest-rank percentile (1-indexed `ceil(p/100 * n)`-th smallest) of a pre-sorted list. */
  private fun nearestRankPercentile(sorted: List<Double>, percentile: Double): Double {
    val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }

  /**
   * [histogram] (grouped-by-value counts) and [percentiles] (see [nearestRankPercentile]) over a
   * lead-time list — shared between the overall summary and each mutant's own breakdown.
   */
  private fun histogramAndPercentiles(
      leadTimes: List<Double>
  ): Pair<Map<Double, Int>, Map<String, Double>> {
    val sorted = leadTimes.sorted()
    val histogram = sorted.groupingBy { it }.eachCount()
    val percentiles =
        if (sorted.isEmpty()) emptyMap()
        else
            listOf(50.0, 75.0, 90.0, 95.0, 99.0).associate { p ->
              "p${p.toInt()}" to nearestRankPercentile(sorted, p)
            }
    return histogram to percentiles
  }

  /** Mutable per-mutant running totals accumulated while scanning detail files in [aggregate]. */
  private class MutantAccumulator {
    var totalTicks = 0
    var neverReproducedCount = 0
    var nonMonotonicCount = 0
    val minLeadTimes = mutableListOf<Double>()
    val maxLeadTimes = mutableListOf<Double>()
  }

  /**
   * Coordinator-side aggregation: reads every worker's detail file (written by [runWorkerSlice],
   * discovered via [discoverDetailFiles]) back in, line by line, and writes one
   * [G0FullLeadTimeSweepSummary] JSON.
   *
   * Can be run standalone against an existing `details/` folder to cheaply regenerate the summary
   * without re-running the (potentially multi-hour) sweep that produced the detail files.
   *
   * @param runId Evaluation run id the analysis was restricted to, or `null` for every run.
   * @return The written [G0FullLeadTimeSweepSummary].
   */
  fun aggregate(runId: Int?): G0FullLeadTimeSweepSummary {
    var totalTicksAnalyzed = 0
    var neverReproducedCount = 0
    var nonMonotonicCount = 0
    val nonMonotonicTickIds = mutableListOf<Long>()
    val minLeadTimes = mutableListOf<Double>()
    val maxLeadTimes = mutableListOf<Double>()
    val byMutant = mutableMapOf<Int, MutantAccumulator>()

    val detailFiles = discoverDetailFiles(runId)
    println("Aggregating ${detailFiles.size} worker detail file(s) for runId=${runId ?: "all"}.")

    for (path in detailFiles) {
      path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val tick = jsonConfiguration.decodeFromString<TickG0FullSweepResult>(line)
        totalTicksAnalyzed++
        val mutantAcc = byMutant.getOrPut(tick.originalMutantId) { MutantAccumulator() }
        mutantAcc.totalTicks++

        if (!tick.isMonotonic) {
          nonMonotonicCount++
          nonMonotonicTickIds += tick.tickId
          mutantAcc.nonMonotonicCount++
        }

        if (tick.minReproducingLeadTimeSeconds == null) {
          neverReproducedCount++
          mutantAcc.neverReproducedCount++
        } else {
          minLeadTimes += tick.minReproducingLeadTimeSeconds
          maxLeadTimes += checkNotNull(tick.maxReproducingLeadTimeSeconds)
          mutantAcc.minLeadTimes += tick.minReproducingLeadTimeSeconds
          mutantAcc.maxLeadTimes += tick.maxReproducingLeadTimeSeconds
        }
      }
    }

    val (minLeadTimeHistogram, minLeadTimePercentiles) = histogramAndPercentiles(minLeadTimes)
    val (maxLeadTimeHistogram, maxLeadTimePercentiles) = histogramAndPercentiles(maxLeadTimes)

    val mutantStats =
        byMutant.entries
            .sortedBy { it.key }
            .map { (mutantId, acc) ->
              val (mutantMinHistogram, mutantMinPercentiles) =
                  histogramAndPercentiles(acc.minLeadTimes)
              val (mutantMaxHistogram, mutantMaxPercentiles) =
                  histogramAndPercentiles(acc.maxLeadTimes)
              G0FullLeadTimeSweepMutantStats(
                  mutantId = mutantId,
                  totalTicks = acc.totalTicks,
                  neverReproducedCount = acc.neverReproducedCount,
                  nonMonotonicCount = acc.nonMonotonicCount,
                  minLeadTimeHistogram = mutantMinHistogram,
                  maxLeadTimeHistogram = mutantMaxHistogram,
                  minLeadTimePercentiles = mutantMinPercentiles,
                  maxLeadTimePercentiles = mutantMaxPercentiles,
              )
            }

    val summary =
        G0FullLeadTimeSweepSummary(
            runId = runId,
            totalTicksAnalyzed = totalTicksAnalyzed,
            neverReproducedCount = neverReproducedCount,
            nonMonotonicCount = nonMonotonicCount,
            nonMonotonicTickIds = nonMonotonicTickIds,
            minLeadTimeHistogram = minLeadTimeHistogram,
            maxLeadTimeHistogram = maxLeadTimeHistogram,
            minLeadTimePercentiles = minLeadTimePercentiles,
            maxLeadTimePercentiles = maxLeadTimePercentiles,
            mutantStats = mutantStats,
        )

    val base = basePath()
    Files.createDirectories(base)
    val summaryPath =
        base.resolve("g0_full_lead_time_sweep_summary_${runId?.toString() ?: "all"}.json")
    summaryPath.writeText(jsonConfiguration.encodeToString(summary))
    println("Finished G0FullLeadTimeSweepAnalysis. Summary written to: $summaryPath")
    return summary
  }
}
