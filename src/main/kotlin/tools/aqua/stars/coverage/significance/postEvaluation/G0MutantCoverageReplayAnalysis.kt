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
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
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
 */
object G0MutantCoverageReplayAnalysis {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "g0_mutant_coverage_replay")

  /**
   * Runs the replay-based G0 mutant coverage check and writes the results as JSON.
   *
   * @param runId Evaluation run id to restrict the analysis to, or `null` to include every run's
   *   flagged ticks.
   */
  fun evaluate(runId: Int? = null) {
    println(
        "Starting G0MutantCoverageReplayAnalysis" +
            (runId?.let { " for runId=$it" } ?: " across all runs") +
            ".")

    val ticks = MetricFailedMonitorsRepository.getAllWithNextTickG0Failed(runId)
    println("Found ${ticks.size} ticks with next_tick_monitor_g0_Accidents_failed = true.")

    val mutants = MutantsRepository.listAll()
    println("Replaying each against ${mutants.size} mutants.")

    val collector = LibsumoDynamicDataCollector()
    val summaries = mutableListOf<TickG0ReplaySummary>()

    for (tick in ticks) {
      val tickId = checkNotNull(tick.id)
      val scenario = ScenarioStartingConfigurationRepository.getById(tick.scenarioConfigId)
      if (scenario == null) {
        println("  Tick $tickId references unknown scenario ${tick.scenarioConfigId} — skipping.")
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
          "  Tick $tickId (tick=${tick.tick}, run=${tick.runId}): original mutant " +
              "${tick.mutantId} failed=${originalResult.g0Failed}, $newMutantsFailedCount/" +
              "${mutantResults.size - 1} other mutants also failed.")

      summaries +=
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
    }

    Files.createDirectories(BASE_PATH)
    val fileName = "g0_mutant_coverage_replay_${runId?.toString() ?: "all"}.json"
    val jsonPath = BASE_PATH.resolve(fileName)
    jsonPath.writeText(jsonConfiguration.encodeToString(summaries))
    println("Finished G0MutantCoverageReplayAnalysis. JSON written to: $jsonPath")
  }
}
