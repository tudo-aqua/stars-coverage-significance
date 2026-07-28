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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0MutantCoverageReplaySummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantG0ReplayStats
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickG0ReplaySummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickMutantG0ReplayResult
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * For every recorded tick (`metric_failed_monitors` row) whose *next* tick was flagged as a G0
 * (Accidents) failure during the original evaluation run, replays that exact recorded scene (via
 * `LibsumoDynamicDataCollector.replayTickForMutant`) once per known mutant, to check:
 * 1. Does the mutant that originally produced the tick still reproduce the G0 failure when
 *    replayed?
 * 2. Do any *other* mutants, substituted into that same recorded scene, additionally trigger a G0
 *    failure?
 *
 * G0's atomic predicate (`tools.aqua.stars.coverage.significance.tsc.g0Accidents`) is a pure,
 * single-tick collision check with no `previous`/`once` history dependence — its only temporal
 * operator is the top-level `globally`, which degenerates to a single-tick check on a tick with no
 * linked `nextTick` (exactly what a one-step replay produces). That makes it safe to call
 * `g0Accidents.holds(nextTick)` directly here, without the full `TSCEvaluation` framework. This
 * does **not** generalize to G1-G4/I1/I2, which dereference tick history the replay doesn't have.
 *
 * ## Parallelism
 *
 * Each replay reloads and steps a full libsumo simulation, and libsumo wraps a single global native
 * simulation per process (`org.eclipse.sumo.libsumo.Simulation` is a static/JNI singleton, not an
 * instantiable handle) — the same reason `RunEvaluation.kt` spawns one JVM *process* per core
 * rather than using threads within one process. This analysis follows the identical pattern:
 * [runWorkerSlice] is the per-process entry point, deterministically claiming every Nth flagged
 * tick (`tick index % numWorkers == workerId`, ticks sorted by id) with no coordination needed
 * between workers.
 *
 * ## Output
 *
 * Each worker streams one [TickG0ReplaySummary] per line (NDJSON) to its own detail file as each
 * tick finishes, flushing after every write. A single big `encodeToString` at the very end of a
 * multi-hour run (the original design) loses *all* progress if the process is killed one tick
 * before completion; streaming means a killed/crashed worker only loses its current tick.
 *
 * After every worker finishes, [aggregate] reads all detail files back (streaming line-by-line, not
 * loading everything into memory at once) and writes one summary JSON with the aggregate statistics
 * — see [G0MutantCoverageReplaySummary].
 */
object G0MutantCoverageReplayAnalysis {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "g0_mutant_coverage_replay")
  private val DETAIL_DIR = BASE_PATH.resolve("details")

  /** Detail (NDJSON) file path for one worker's share of ticks. */
  private fun detailFilePath(runId: Int?, workerId: Int): Path =
      DETAIL_DIR.resolve(
          "g0_mutant_coverage_replay_${runId?.toString() ?: "all"}_worker$workerId.jsonl")

  /**
   * Worker entry point: replays this worker's deterministic share of flagged ticks (every tick at
   * index `i` where `i % numWorkers == workerId`, ticks ordered by id) against every known mutant,
   * streaming one [TickG0ReplaySummary] per line to this worker's own detail file as each tick
   * completes.
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
        "[worker-$workerId] Replaying ${myTicks.size}/${allTicks.size} flagged ticks" +
            (runId?.let { " for runId=$it" } ?: " across all runs") +
            ".")

    val mutants = MutantsRepository.listAll()
    val collector = LibsumoDynamicDataCollector()

    Files.createDirectories(DETAIL_DIR)
    detailFilePath(runId, workerId).bufferedWriter().use { writer ->
      for (tick in myTicks) {
        val tickId = checkNotNull(tick.id)
        val scenario = ScenarioStartingConfigurationRepository.getById(tick.scenarioConfigId)
        if (scenario == null) {
          println(
              "[worker-$workerId] Tick $tickId references unknown scenario " +
                  "${tick.scenarioConfigId} — skipping.")
          continue
        }

        val mutantResults =
            mutants.map { mutant ->
              val mutantId = checkNotNull(mutant.id)
              val nextTick = collector.replayTickForMutant(tick.runId, tick, scenario, mutantId)
              TickMutantG0ReplayResult(
                  mutantId = mutantId,
                  mutantNumber = mutant.mutantNumber,
                  className = mutant.className,
                  isOriginalMutant = mutantId == tick.mutantId,
                  g0Failed = nextTick?.let { !g0Accidents.holds(it) },
              )
            }

        val originalResult = mutantResults.first { it.isOriginalMutant }
        val newMutantsFailedCount =
            mutantResults.count { !it.isOriginalMutant && it.g0Failed == true }

        println(
            "[worker-$workerId] Tick $tickId (tick=${tick.tick}, run=${tick.runId}): original " +
                "mutant ${tick.mutantId} failed=${originalResult.g0Failed}, " +
                "$newMutantsFailedCount/${mutantResults.size - 1} other mutants also failed.")

        val summary =
            TickG0ReplaySummary(
                tickId = tickId,
                originalTick = tick.tick,
                runId = tick.runId,
                scenarioConfigId = tick.scenarioConfigId,
                originalMutantId = tick.mutantId,
                originalMutantFailed = originalResult.g0Failed,
                newMutantsFailedCount = newMutantsFailedCount,
                mutantResults = mutantResults,
            )
        writer.write(jsonConfiguration.encodeToString(summary))
        writer.newLine()
        writer.flush()
      }
    }
    println("[worker-$workerId] Finished. Detail written to: ${detailFilePath(runId, workerId)}")
  }

  /**
   * Coordinator-side aggregation: reads every worker's detail file (written by [runWorkerSlice])
   * back in, line by line, and writes one [G0MutantCoverageReplaySummary] JSON.
   *
   * Must only be called after every worker in `0 until numWorkers` has completed successfully — the
   * caller (`RunG0MutantCoverageReplay.kt`) enforces this via `ProcessGroupRunner.awaitAll`, since
   * aggregating over a partial/killed worker's file would silently under-report failures.
   *
   * @param runId Evaluation run id the analysis was restricted to, or `null` for every run.
   * @param numWorkers Total number of workers that were spawned.
   * @return The written [G0MutantCoverageReplaySummary].
   */
  fun aggregate(runId: Int?, numWorkers: Int): G0MutantCoverageReplaySummary {
    val mutants = MutantsRepository.listAll()
    val originalTickCount = mutableMapOf<Int, Int>()
    val originalTickReproducedCount = mutableMapOf<Int, Int>()
    val newKillTickIds = mutableMapOf<Int, MutableList<Int>>()
    mutants.forEach { mutant ->
      val mutantId = checkNotNull(mutant.id)
      originalTickCount[mutantId] = 0
      originalTickReproducedCount[mutantId] = 0
      newKillTickIds[mutantId] = mutableListOf()
    }

    var totalTicksAnalyzed = 0
    var originalMutantReproducedCount = 0
    var originalMutantNotReproducedCount = 0
    var originalMutantInconclusiveCount = 0
    val unavoidableTickIds = mutableListOf<Int>()

    for (workerId in 0 until numWorkers) {
      val path = detailFilePath(runId, workerId)
      if (!path.exists()) continue

      path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val tick = jsonConfiguration.decodeFromString<TickG0ReplaySummary>(line)
        totalTicksAnalyzed++

        when (tick.originalMutantFailed) {
          true -> originalMutantReproducedCount++
          false -> originalMutantNotReproducedCount++
          null -> originalMutantInconclusiveCount++
        }

        originalTickCount.merge(tick.originalMutantId, 1, Int::plus)
        if (tick.originalMutantFailed == true) {
          originalTickReproducedCount.merge(tick.originalMutantId, 1, Int::plus)
        }

        if (tick.newMutantsFailedCount == tick.mutantResults.size - 1) {
          unavoidableTickIds += tick.tickId
        }

        tick.mutantResults.forEach { result ->
          if (!result.isOriginalMutant && result.g0Failed == true) {
            newKillTickIds.getOrPut(result.mutantId) { mutableListOf() } += tick.tickId
          }
        }
      }
    }

    val mutantStats =
        mutants.map { mutant ->
          val mutantId = checkNotNull(mutant.id)
          MutantG0ReplayStats(
              mutantId = mutantId,
              mutantNumber = mutant.mutantNumber,
              className = mutant.className,
              originalTickCount = originalTickCount.getValue(mutantId),
              originalTickReproducedCount = originalTickReproducedCount.getValue(mutantId),
              newKillTickIds = newKillTickIds.getValue(mutantId),
          )
        }

    val summary =
        G0MutantCoverageReplaySummary(
            runId = runId,
            totalTicksAnalyzed = totalTicksAnalyzed,
            totalMutants = mutants.size,
            originalMutantReproducedCount = originalMutantReproducedCount,
            originalMutantNotReproducedCount = originalMutantNotReproducedCount,
            originalMutantInconclusiveCount = originalMutantInconclusiveCount,
            unavoidableTickCount = unavoidableTickIds.size,
            unavoidableTickIds = unavoidableTickIds,
            mutantsWithNewKillsCount = mutantStats.count { it.newKillTickIds.isNotEmpty() },
            mutantStats = mutantStats,
        )

    Files.createDirectories(BASE_PATH)
    val summaryPath =
        BASE_PATH.resolve("g0_mutant_coverage_replay_summary_${runId?.toString() ?: "all"}.json")
    summaryPath.writeText(jsonConfiguration.encodeToString(summary))
    println("Finished G0MutantCoverageReplayAnalysis. Summary written to: $summaryPath")
    return summary
  }
}
