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
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DivergenceStopReason
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0DivergenceLeadTimeSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.LeadTimeStepResult
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickG0DivergenceResult
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * For every recorded tick (`metric_failed_monitors` row) whose *next* tick was flagged as a G0
 * (Accidents) failure during the original evaluation run, replays that exact recorded scene's own
 * original mutant (`tick.mutantId`) repeatedly, stepping the reconstruction *backward* in the
 * simulation's native 0.1s step length (0.0, 0.1, 0.2, ...) via [LeadTimeReplay] +
 * `LibsumoDynamicDataCollector.replayFromTickForDuration`, to find the per-tick "divergence lead
 * time" — the first lead time at which that same mutant, given more run-up time, no longer
 * reproduces the recorded accident.
 *
 * This answers a different question than
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis]: that
 * analysis asks "which mutants also fail on this exact scene, at one caller-chosen lead time?";
 * this one asks "how much lead time can the *original* mutant tolerate before it stops reproducing
 * its own known failure?" — collecting that across every flagged tick gives a distribution to pick
 * a sensible lead-time threshold from for the other analysis's `--leadTimeSeconds` flag: large
 * enough to give a substituted mutant meaningful reaction time, small enough that the original
 * mutant still reliably reproduces its own recorded failures at that lead time.
 *
 * Shares the same `g0Accidents.holds(nextTick)` reasoning (safe without the full `TSCEvaluation`
 * framework) and one-process-per-core parallelism rationale as
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis] — see its
 * docs for both. [runWorkerSlice] is the per-process entry point, deterministically claiming every
 * Nth flagged tick; [aggregate] reads every worker's streamed NDJSON detail file back and writes
 * one summary JSON.
 */
object G0DivergenceLeadTimeAnalysis {

  private fun basePath(): Path = Path.of(POST_EVALUATION_BASE_DIR, "g0_lead_time_divergence")

  private fun detailDir(): Path = basePath().resolve("details")

  /** Detail (NDJSON) file path for one worker's share of ticks. */
  private fun detailFilePath(runId: Int?, workerId: Int): Path =
      detailDir()
          .resolve("g0_lead_time_divergence_${runId?.toString() ?: "all"}_worker$workerId.jsonl")

  /**
   * Worker entry point: sweeps this worker's deterministic share of flagged ticks (every tick at
   * index `i` where `i % numWorkers == workerId`, ticks ordered by id) backward through lead times
   * against each tick's own original mutant, streaming one [TickG0DivergenceResult] per line to
   * this worker's own detail file as each tick completes.
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

    analyzeTicks(ticks = myTicks, runId = runId, workerId = workerId)
  }

  /**
   * Runs the backward lead-time sweep for every tick in [ticks] against its own original mutant,
   * streaming one [TickG0DivergenceResult] per line to this worker's detail file as each tick
   * completes.
   *
   * Holds the actual sweep work factored out of [runWorkerSlice] so it can also be driven manually
   * — e.g. to re-sweep a single tick — without going through the `numWorkers`/`workerId` slicing
   * [runWorkerSlice] uses to split the full set of flagged ticks across worker processes.
   *
   * @param ticks The ticks to sweep, in order.
   * @param runId Evaluation run id the ticks were restricted to, or `null` for every run — only
   *   used to name the detail file, must match how [ticks] was queried.
   * @param workerId Identifies the detail file this call writes/appends to, and is used in log
   *   output — for a manual, non-worker call, any id not colliding with a real worker's is fine.
   */
  fun analyzeTicks(ticks: List<MetricFailedMonitorsEntry>, runId: Int?, workerId: Int) {
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

        val steps = mutableListOf<LeadTimeStepResult>()
        var previousStartTickId: Long? = null
        var stopReason: DivergenceStopReason
        var divergenceLeadTimeSeconds: Double? = null
        // Integer tenths of a second, to step in exact 0.1s increments without floating-point
        // drift from repeatedly adding 0.1.
        var tenths = 0

        while (true) {
          val leadTimeSeconds = tenths / 10.0
          val startTick = LeadTimeReplay.findStartTick(tick, leadTimeSeconds, candidates)
          val startTickId = checkNotNull(startTick.id)
          if (previousStartTickId != null && startTickId == previousStartTickId) {
            stopReason = DivergenceStopReason.REACHED_SCENARIO_START
            break
          }
          previousStartTickId = startTickId

          val stepCount = LeadTimeReplay.stepCountThroughOriginal(tick, startTick)
          val replaySteps =
              collector.replayFromTickForDuration(
                  tick.runId, startTick, scenario, tick.mutantId, stepCount)
          if (replaySteps.isEmpty()) {
            stopReason = DivergenceStopReason.EGO_LEFT_SIMULATION
            break
          }

          val g0Failed = replaySteps.any { !g0Accidents.holds(it) }
          steps += LeadTimeStepResult(leadTimeSeconds, startTickId, stepCount, g0Failed)

          if (!g0Failed) {
            stopReason = DivergenceStopReason.DIVERGED
            divergenceLeadTimeSeconds = leadTimeSeconds
            break
          }
          tenths++
        }

        println(
            "[worker-$workerId] Tick $tickId (tick=${tick.tick}, run=${tick.runId}, " +
                "mutant=${tick.mutantId}): ${steps.size} lead-time step(s) replayed, " +
                "stopReason=$stopReason" +
                (divergenceLeadTimeSeconds?.let { ", divergenceLeadTimeSeconds=$it" } ?: "") +
                ".")

        val result =
            TickG0DivergenceResult(
                tickId = tickId,
                originalTick = tick.tick,
                runId = tick.runId,
                scenarioConfigId = tick.scenarioConfigId,
                originalMutantId = tick.mutantId,
                steps = steps,
                divergenceLeadTimeSeconds = divergenceLeadTimeSeconds,
                stopReason = stopReason,
            )
        writer.write(jsonConfiguration.encodeToString(result))
        writer.newLine()
        writer.flush()
      }
    }
    println("[worker-$workerId] Finished. Detail written to: " + detailFilePath(runId, workerId))
  }

  /**
   * Finds every worker detail file for [runId] currently on disk, by directory listing rather than
   * an assumed `0 until numWorkers` range — same rationale as
   * [tools.aqua.stars.coverage.significance.postEvaluation.G0MutantCoverageReplayAnalysis]'s
   * `discoverDetailFiles`.
   */
  private fun discoverDetailFiles(runId: Int?): List<Path> {
    val prefix = "g0_lead_time_divergence_${runId?.toString() ?: "all"}_worker"
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
   * Coordinator-side aggregation: reads every worker's detail file (written by [runWorkerSlice],
   * discovered via [discoverDetailFiles]) back in, line by line, and writes one
   * [G0DivergenceLeadTimeSummary] JSON.
   *
   * Can be run standalone against an existing `details/` folder to cheaply regenerate the summary
   * without re-running the (potentially multi-hour) sweep that produced the detail files.
   *
   * @param runId Evaluation run id the analysis was restricted to, or `null` for every run.
   * @return The written [G0DivergenceLeadTimeSummary].
   */
  fun aggregate(runId: Int?): G0DivergenceLeadTimeSummary {
    var totalTicksAnalyzed = 0
    var divergedCount = 0
    var immediateDivergenceCount = 0
    var egoLeftSimulationCount = 0
    var reachedScenarioStartCount = 0
    val divergenceLeadTimes = mutableListOf<Double>()

    val detailFiles = discoverDetailFiles(runId)
    println("Aggregating ${detailFiles.size} worker detail file(s) for runId=${runId ?: "all"}.")

    for (path in detailFiles) {
      path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val tick = jsonConfiguration.decodeFromString<TickG0DivergenceResult>(line)
        totalTicksAnalyzed++

        when (tick.stopReason) {
          DivergenceStopReason.DIVERGED -> {
            divergedCount++
            val leadTime = checkNotNull(tick.divergenceLeadTimeSeconds)
            divergenceLeadTimes += leadTime
            if (leadTime == 0.0) immediateDivergenceCount++
          }
          DivergenceStopReason.EGO_LEFT_SIMULATION -> egoLeftSimulationCount++
          DivergenceStopReason.REACHED_SCENARIO_START -> reachedScenarioStartCount++
        }
      }
    }

    val sortedLeadTimes = divergenceLeadTimes.sorted()
    val histogram = sortedLeadTimes.groupingBy { it }.eachCount()
    val percentiles =
        if (sortedLeadTimes.isEmpty()) emptyMap()
        else
            listOf(50.0, 75.0, 90.0, 95.0, 99.0).associate { p ->
              "p${p.toInt()}" to nearestRankPercentile(sortedLeadTimes, p)
            }

    val summary =
        G0DivergenceLeadTimeSummary(
            runId = runId,
            totalTicksAnalyzed = totalTicksAnalyzed,
            divergedCount = divergedCount,
            immediateDivergenceCount = immediateDivergenceCount,
            egoLeftSimulationCount = egoLeftSimulationCount,
            reachedScenarioStartCount = reachedScenarioStartCount,
            divergenceLeadTimes = divergenceLeadTimes,
            histogram = histogram,
            percentiles = percentiles,
        )

    val base = basePath()
    Files.createDirectories(base)
    val summaryPath =
        base.resolve("g0_lead_time_divergence_summary_${runId?.toString() ?: "all"}.json")
    summaryPath.writeText(jsonConfiguration.encodeToString(summary))
    println("Finished G0DivergenceLeadTimeAnalysis. Summary written to: $summaryPath")
    return summary
  }
}
