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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random
import kotlin.streams.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFailedMonitorsEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.ScenarioStartingConfigurationEntry
import tools.aqua.stars.coverage.significance.db.repositories.MetricFailedMonitorsRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DeterminismReplayAttemptResult
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.EpsilonHeadroomStats
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ExceptionCapture
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0FullLeadTimeSweepSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.G0OutcomeMismatch
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ReplayAttemptOutcome
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ReplayDeterminismMutantStats
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ReplayDeterminismSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.SampleSource
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.VehicleFieldMismatch
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.VehicleSetMismatch
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.utils.jsonConfiguration
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.libSumo.LibsumoDynamicDataCollector

/**
 * Live determinism check: for a bounded, broad sample of flagged ticks (see [buildSampleList]),
 * calls `LibsumoDynamicDataCollector.replayFromTickForDuration` **twice** with identical arguments
 * at each of a reduced number of lead times, and diffs the two results at the raw per-vehicle level
 * and at the derived `g0Accidents` level — answering "is replaying from a (reconstructed) tick
 * actually deterministic", independently of
 * [tools.aqua.stars.coverage.significance.postEvaluation.G0FullLeadTimeSweepAnalysis]'s existing
 * `fullReplayMismatchCount` check (which only ever compares one replay against the *original*
 * recording, never two independent replays against each other, and never at the raw vehicle level).
 *
 * Shares the same one-process-per-core worker architecture and parallelism rationale as
 * [G0FullLeadTimeSweepAnalysis] — see its docs. [runWorkerSlice] is the per-process entry point;
 * [aggregate] reads every worker's streamed NDJSON detail file back and writes one summary JSON.
 */
object ReplayDeterminismAnalysis {

  // Physically insignificant thresholds (1mm, 1mm/s, 1cm/s^2) rather than a float-rounding fudge:
  // two independent reload-and-step runs of the same inputs should be bit-identical if the whole
  // pipeline really is deterministic, so a real divergence should land far above these, not near
  // them. Acceleration gets the loosest epsilon since getCurrentTimeStep's acceleration is
  // sometimes a finite-difference (speed delta / step length) fallback, which inherits and
  // amplifies any speed-level noise. maxAbsDiff* is always recorded on every attempt (even
  // successful ones) so the aggregated report can show empirically whether this headroom was
  // generous or marginal.
  private const val POSITION_EPSILON_METERS = 1e-3f
  private const val SPEED_EPSILON_MPS = 1e-3f
  private const val ACCEL_EPSILON_MPS2 = 1e-2f

  // Fixed seed so the *sample itself* is reproducible across invocations with the same flags --
  // any variability a run finds should be attributable to the replay mechanism under test, never
  // to "which ticks happened to get sampled this time."
  private const val SAMPLE_SEED = 42L

  private fun basePath(): Path = Path.of(POST_EVALUATION_BASE_DIR, "replay_diagnostics")

  private fun detailDir(): Path = basePath().resolve("details")

  private fun detailFilePath(runId: Int?, workerId: Int): Path =
      detailDir().resolve("replay_determinism_${runId?.toString() ?: "all"}_worker$workerId.jsonl")

  private fun existingFullSweepSummaryPath(): Path =
      Path.of(
          POST_EVALUATION_BASE_DIR,
          "g0_full_lead_time_sweep",
          "g0_full_lead_time_sweep_summary_all.json")

  /**
   * Builds the sample of `(tick, source)` pairs this analysis will double-replay: up to
   * [ticksPerMutant] ticks per mutant (across at most [maxMutants] mutants, or every mutant with a
   * flagged tick if `null`), drawn via a fixed-seed shuffle so the sample is reproducible, plus up
   * to [maxNonMonotonicSample] ticks drawn the same way from the existing
   * [G0FullLeadTimeSweepSummary.nonMonotonicTickIds] (read from the already-produced "all runs"
   * summary at [existingFullSweepSummaryPath] — skipped with a warning if that file doesn't exist
   * yet, e.g. on a fresh checkout before the full sweep has ever run). A tick present in both parts
   * keeps the [SampleSource.NON_MONOTONIC_TARGETED] tag, since that's the more specific
   * classification.
   */
  private fun buildSampleList(
      allFlaggedTicks: List<MetricFailedMonitorsEntry>,
      ticksPerMutant: Int,
      maxMutants: Int?,
      maxNonMonotonicSample: Int,
  ): List<Pair<MetricFailedMonitorsEntry, SampleSource>> {
    val byId = allFlaggedTicks.associateBy { checkNotNull(it.id) }

    val byMutant = allFlaggedTicks.groupBy { it.mutantId }
    val mutantIds =
        byMutant.keys.sorted().let { if (maxMutants != null) it.take(maxMutants) else it }

    val sources = linkedMapOf<Long, SampleSource>()
    for (mutantId in mutantIds) {
      val picked = byMutant.getValue(mutantId).shuffled(Random(SAMPLE_SEED)).take(ticksPerMutant)
      for (t in picked) sources[checkNotNull(t.id)] = SampleSource.RANDOM
    }

    val summaryPath = existingFullSweepSummaryPath()
    if (summaryPath.exists()) {
      runCatching {
            val summary =
                jsonConfiguration.decodeFromString<G0FullLeadTimeSweepSummary>(
                    summaryPath.readText())
            val candidates = summary.nonMonotonicTickIds.mapNotNull { byId[it] }
            candidates.shuffled(Random(SAMPLE_SEED)).take(maxNonMonotonicSample)
          }
          .onSuccess { picked ->
            for (t in picked) sources[checkNotNull(t.id)] = SampleSource.NON_MONOTONIC_TARGETED
          }
          .onFailure {
            println("Warning: could not read non-monotonic sample from $summaryPath: ${it.message}")
          }
    } else {
      println(
          "No existing sweep summary found at $summaryPath - skipping non-monotonic targeted sample.")
    }

    return sources.entries.sortedBy { it.key }.map { (id, source) -> byId.getValue(id) to source }
  }

  /**
   * Worker entry point: builds the full sample (see [buildSampleList]) then takes this worker's
   * deterministic share of it (every entry at index `i` where `i % numWorkers == workerId`).
   *
   * @param runId Evaluation run id to restrict to, or `null` to include every run.
   * @param workerId This worker's index, in `0 until numWorkers`.
   * @param numWorkers Total number of workers splitting the sample.
   * @param ticksPerMutant Random-sample depth per mutant.
   * @param maxMutants Cap on distinct mutants sampled, or `null` for every mutant with a flagged
   *   tick.
   * @param maxNonMonotonicSample Cap on the targeted sample drawn from the existing
   *   `nonMonotonicTickIds`.
   * @param maxLeadTimeSteps Cap on lead-time depth (in 0.1s steps) per sampled tick.
   */
  fun runWorkerSlice(
      runId: Int?,
      workerId: Int,
      numWorkers: Int,
      ticksPerMutant: Int,
      maxMutants: Int?,
      maxNonMonotonicSample: Int,
      maxLeadTimeSteps: Int,
  ) {
    val allFlaggedTicks = MetricFailedMonitorsRepository.getAllWithNextTickG0Failed(runId)
    val sample = buildSampleList(allFlaggedTicks, ticksPerMutant, maxMutants, maxNonMonotonicSample)
    val mySample = sample.filterIndexed { index, _ -> index % numWorkers == workerId }
    println(
        "[worker-$workerId] Determinism-checking ${mySample.size}/${sample.size} sampled ticks" +
            (runId?.let { " for runId=$it" } ?: " across all runs") +
            ".")

    diagnoseTicks(mySample, runId, workerId, maxLeadTimeSteps)
  }

  /**
   * Runs the live double-replay check for every `(tick, source)` in [sample], streaming one
   * [DeterminismReplayAttemptResult] per line to this worker's detail file as each lead-time step
   * completes. Factored out of [runWorkerSlice] so it can also be driven manually against an
   * explicit tick list.
   */
  fun diagnoseTicks(
      sample: List<Pair<MetricFailedMonitorsEntry, SampleSource>>,
      runId: Int?,
      workerId: Int,
      maxLeadTimeSteps: Int,
  ) {
    val collector = LibsumoDynamicDataCollector()
    val candidatesCache = mutableMapOf<Pair<Int, Int>, List<MetricFailedMonitorsEntry>>()

    Files.createDirectories(detailDir())
    detailFilePath(runId, workerId).bufferedWriter().use { writer ->
      for ((tick, sampleSource) in sample) {
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

        var previousStartTickId: Long? = null
        var tenths = 0
        var attemptsWritten = 0

        while (tenths < maxLeadTimeSteps) {
          val leadTimeSeconds = tenths / 10.0
          val startTick = LeadTimeReplay.findStartTick(tick, leadTimeSeconds, candidates)
          val startTickId = checkNotNull(startTick.id)
          if (previousStartTickId != null && startTickId == previousStartTickId) break
          previousStartTickId = startTickId

          val stepCount = LeadTimeReplay.stepCountThroughOriginal(tick, startTick)

          val attempt =
              runCatching {
                    classifyAttempt(
                        collector,
                        tick,
                        scenario,
                        startTick,
                        sampleSource,
                        leadTimeSeconds,
                        stepCount)
                  }
                  .getOrElse { e ->
                    // Belt-and-suspenders: classifyAttempt already catches exceptions from the two
                    // replay calls themselves - this guards against anything else going wrong (e.g.
                    // JSON encoding) so one bad tick still can't kill the whole worker process.
                    DeterminismReplayAttemptResult(
                        tickId = tickId,
                        originalTick = tick.tick,
                        runId = tick.runId,
                        scenarioConfigId = tick.scenarioConfigId,
                        mutantId = tick.mutantId,
                        sampleSource = sampleSource,
                        leadTimeSeconds = leadTimeSeconds,
                        startTickId = startTickId,
                        requestedStepCount = stepCount,
                        outcome = ReplayAttemptOutcome.EXCEPTION,
                        actualStepCountA = null,
                        actualStepCountB = null,
                        exceptionA = captureException(e),
                    )
                  }

          writer.write(jsonConfiguration.encodeToString(attempt))
          writer.newLine()
          writer.flush()
          attemptsWritten++
          tenths++
        }

        println(
            "[worker-$workerId] Tick $tickId ($sampleSource): $attemptsWritten lead-time attempt(s) checked.")
      }
    }
    println("[worker-$workerId] Finished. Detail written to: " + detailFilePath(runId, workerId))
  }

  private fun captureException(t: Throwable): ExceptionCapture =
      ExceptionCapture(
          exceptionClass = t::class.qualifiedName ?: t::class.simpleName ?: "Unknown",
          message = t.message,
          stackTraceSnippet = t.stackTraceToString().lines().take(10).joinToString("\n"),
      )

  private fun floatsClose(a: Float, b: Float, absEpsilon: Float): Boolean =
      a == b || abs(a - b) <= absEpsilon

  /**
   * Calls `replayFromTickForDuration` twice with identical arguments and diffs the two results. The
   * two calls' exceptions are captured separately ([DeterminismReplayAttemptResult.exceptionA]/
   * `exceptionB`) rather than merged into one field: if both runs throw the *same* exception
   * class/message, that is a reproducible placement bug (e.g. a recorded snapshot with no ego
   * entry), not run-to-run flakiness; if only one throws (or they throw differently), that
   * asymmetry *is* the nondeterminism finding.
   */
  private fun classifyAttempt(
      collector: LibsumoDynamicDataCollector,
      tick: MetricFailedMonitorsEntry,
      scenario: ScenarioStartingConfigurationEntry,
      startTick: MetricFailedMonitorsEntry,
      sampleSource: SampleSource,
      leadTimeSeconds: Double,
      stepCount: Int,
  ): DeterminismReplayAttemptResult {
    val tickId = checkNotNull(tick.id)
    val startTickId = checkNotNull(startTick.id)

    fun result(
        outcome: ReplayAttemptOutcome,
        actualStepCountA: Int? = null,
        actualStepCountB: Int? = null,
        exceptionA: ExceptionCapture? = null,
        exceptionB: ExceptionCapture? = null,
        vehicleSetMismatches: List<VehicleSetMismatch> = emptyList(),
        vehicleFieldMismatches: List<VehicleFieldMismatch> = emptyList(),
        g0OutcomeMismatches: List<G0OutcomeMismatch> = emptyList(),
        maxAbsDiffPositionMeters: Double? = null,
        maxAbsDiffSpeedMps: Double? = null,
        maxAbsDiffAccelMps2: Double? = null,
    ) =
        DeterminismReplayAttemptResult(
            tickId = tickId,
            originalTick = tick.tick,
            runId = tick.runId,
            scenarioConfigId = tick.scenarioConfigId,
            mutantId = tick.mutantId,
            sampleSource = sampleSource,
            leadTimeSeconds = leadTimeSeconds,
            startTickId = startTickId,
            requestedStepCount = stepCount,
            outcome = outcome,
            actualStepCountA = actualStepCountA,
            actualStepCountB = actualStepCountB,
            exceptionA = exceptionA,
            exceptionB = exceptionB,
            vehicleSetMismatches = vehicleSetMismatches,
            vehicleFieldMismatches = vehicleFieldMismatches,
            g0OutcomeMismatches = g0OutcomeMismatches,
            maxAbsDiffPositionMeters = maxAbsDiffPositionMeters,
            maxAbsDiffSpeedMps = maxAbsDiffSpeedMps,
            maxAbsDiffAccelMps2 = maxAbsDiffAccelMps2,
        )

    val resultA = runCatching {
      collector.replayFromTickForDuration(tick.runId, startTick, scenario, tick.mutantId, stepCount)
    }
    val resultB = runCatching {
      collector.replayFromTickForDuration(tick.runId, startTick, scenario, tick.mutantId, stepCount)
    }

    val exceptionA = resultA.exceptionOrNull()?.let(::captureException)
    val exceptionB = resultB.exceptionOrNull()?.let(::captureException)
    if (exceptionA != null || exceptionB != null) {
      return result(
          ReplayAttemptOutcome.EXCEPTION,
          actualStepCountA = resultA.getOrNull()?.size,
          actualStepCountB = resultB.getOrNull()?.size,
          exceptionA = exceptionA,
          exceptionB = exceptionB,
      )
    }

    val stepsA = resultA.getOrThrow()
    val stepsB = resultB.getOrThrow()

    if (stepsA.size != stepsB.size) {
      return result(ReplayAttemptOutcome.NONDETERMINISTIC_STEP_COUNT, stepsA.size, stepsB.size)
    }
    if (stepsA.isEmpty()) {
      return result(ReplayAttemptOutcome.INCONCLUSIVE_EMPTY_AT_PLACEMENT, 0, 0)
    }

    val vehicleSetMismatches = mutableListOf<VehicleSetMismatch>()
    val vehicleFieldMismatches = mutableListOf<VehicleFieldMismatch>()
    val g0OutcomeMismatches = mutableListOf<G0OutcomeMismatch>()
    var maxAbsDiffPosition = 0.0
    var maxAbsDiffSpeed = 0.0
    var maxAbsDiffAccel = 0.0

    fun diffStep(stepIndex: Int, a: TimeStep, b: TimeStep) {
      val vehiclesA = a.vehiclesInTick.associateBy { it.vehicleId }
      val vehiclesB = b.vehiclesInTick.associateBy { it.vehicleId }
      val onlyInA = (vehiclesA.keys - vehiclesB.keys).sorted()
      val onlyInB = (vehiclesB.keys - vehiclesA.keys).sorted()
      if (onlyInA.isNotEmpty() || onlyInB.isNotEmpty()) {
        vehicleSetMismatches += VehicleSetMismatch(stepIndex, onlyInA, onlyInB)
      }

      for (vehId in (vehiclesA.keys intersect vehiclesB.keys).sorted()) {
        val va = vehiclesA.getValue(vehId)
        val vb = vehiclesB.getValue(vehId)

        val posDiff = abs(va.positionOnLaneMeters - vb.positionOnLaneMeters).toDouble()
        maxAbsDiffPosition = max(maxAbsDiffPosition, posDiff)
        if (!floatsClose(
            va.positionOnLaneMeters, vb.positionOnLaneMeters, POSITION_EPSILON_METERS)) {
          vehicleFieldMismatches +=
              VehicleFieldMismatch(
                  stepIndex,
                  vehId,
                  "positionOnLaneMeters",
                  va.positionOnLaneMeters.toString(),
                  vb.positionOnLaneMeters.toString(),
                  posDiff)
        }

        val speedDiff = abs(va.speedMetersPerSecond - vb.speedMetersPerSecond).toDouble()
        maxAbsDiffSpeed = max(maxAbsDiffSpeed, speedDiff)
        if (!floatsClose(va.speedMetersPerSecond, vb.speedMetersPerSecond, SPEED_EPSILON_MPS)) {
          vehicleFieldMismatches +=
              VehicleFieldMismatch(
                  stepIndex,
                  vehId,
                  "speedMetersPerSecond",
                  va.speedMetersPerSecond.toString(),
                  vb.speedMetersPerSecond.toString(),
                  speedDiff)
        }

        val accelDiff =
            abs(va.accelerationMetersPerSecondSquared - vb.accelerationMetersPerSecondSquared)
                .toDouble()
        maxAbsDiffAccel = max(maxAbsDiffAccel, accelDiff)
        if (!floatsClose(
            va.accelerationMetersPerSecondSquared,
            vb.accelerationMetersPerSecondSquared,
            ACCEL_EPSILON_MPS2)) {
          vehicleFieldMismatches +=
              VehicleFieldMismatch(
                  stepIndex,
                  vehId,
                  "accelerationMetersPerSecondSquared",
                  va.accelerationMetersPerSecondSquared.toString(),
                  vb.accelerationMetersPerSecondSquared.toString(),
                  accelDiff)
        }

        if (va.currentLane.laneIndex != vb.currentLane.laneIndex) {
          vehicleFieldMismatches +=
              VehicleFieldMismatch(
                  stepIndex,
                  vehId,
                  "laneIndex",
                  va.currentLane.laneIndex.toString(),
                  vb.currentLane.laneIndex.toString())
        }
        if (va.vehicleType.typeId != vb.vehicleType.typeId) {
          vehicleFieldMismatches +=
              VehicleFieldMismatch(
                  stepIndex, vehId, "vehicleTypeId", va.vehicleType.typeId, vb.vehicleType.typeId)
        }
      }

      val g0A = g0Accidents.holds(a)
      val g0B = g0Accidents.holds(b)
      if (g0A != g0B) g0OutcomeMismatches += G0OutcomeMismatch(stepIndex, g0A, g0B)
    }

    for (stepIndex in stepsA.indices) diffStep(stepIndex, stepsA[stepIndex], stepsB[stepIndex])

    val outcome =
        when {
          vehicleSetMismatches.isNotEmpty() || vehicleFieldMismatches.isNotEmpty() ->
              ReplayAttemptOutcome.NONDETERMINISTIC_VEHICLE_DATA
          g0OutcomeMismatches.isNotEmpty() -> ReplayAttemptOutcome.NONDETERMINISTIC_G0_OUTCOME
          stepsA.size < stepCount -> ReplayAttemptOutcome.TRUNCATED_MID_LOOP
          else -> ReplayAttemptOutcome.SUCCESS
        }

    return result(
        outcome,
        actualStepCountA = stepsA.size,
        actualStepCountB = stepsB.size,
        vehicleSetMismatches = vehicleSetMismatches,
        vehicleFieldMismatches = vehicleFieldMismatches,
        g0OutcomeMismatches = g0OutcomeMismatches,
        maxAbsDiffPositionMeters = maxAbsDiffPosition,
        maxAbsDiffSpeedMps = maxAbsDiffSpeed,
        maxAbsDiffAccelMps2 = maxAbsDiffAccel,
    )
  }

  private fun discoverDetailFiles(runId: Int?): List<Path> {
    val prefix = "replay_determinism_${runId?.toString() ?: "all"}_worker"
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

  private fun <K> MutableMap<K, Int>.bump(key: K) {
    this[key] = (this[key] ?: 0) + 1
  }

  private fun nearestRankPercentile(sorted: List<Double>, percentile: Double): Double {
    val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }

  private fun epsilonHeadroomStats(values: List<Double>): EpsilonHeadroomStats {
    if (values.isEmpty())
        return EpsilonHeadroomStats(maxAbsDiff = 0.0, p99AbsDiff = 0.0, p50AbsDiff = 0.0)
    val sorted = values.sorted()
    return EpsilonHeadroomStats(
        maxAbsDiff = sorted.last(),
        p99AbsDiff = nearestRankPercentile(sorted, 99.0),
        p50AbsDiff = nearestRankPercentile(sorted, 50.0),
    )
  }

  /**
   * Coordinator-side aggregation: reads every worker's detail file back in, line by line, and
   * writes one [ReplayDeterminismSummary] JSON.
   *
   * @param runId Evaluation run id the analysis was restricted to, or `null` for every run.
   * @return The written [ReplayDeterminismSummary].
   */
  fun aggregate(runId: Int?): ReplayDeterminismSummary {
    var totalAttempts = 0
    val sampledTickIds = mutableSetOf<Long>()
    val mutantTotals = mutableMapOf<Int, Int>()
    val outcomeCounts = mutableMapOf<ReplayAttemptOutcome, Int>()
    val outcomeCountsBySampleSource =
        mutableMapOf<SampleSource, MutableMap<ReplayAttemptOutcome, Int>>()
    val leadTimeBucketOutcomeCounts = mutableMapOf<String, MutableMap<ReplayAttemptOutcome, Int>>()
    val byMutant = mutableMapOf<Int, MutableMap<ReplayAttemptOutcome, Int>>()
    val byMutantLeadBucket =
        mutableMapOf<Int, MutableMap<String, MutableMap<ReplayAttemptOutcome, Int>>>()
    val exampleTickIds = mutableMapOf<ReplayAttemptOutcome, MutableList<Long>>()
    val positionDiffs = mutableListOf<Double>()
    val speedDiffs = mutableListOf<Double>()
    val accelDiffs = mutableListOf<Double>()

    val detailFiles = discoverDetailFiles(runId)
    println("Aggregating ${detailFiles.size} worker detail file(s) for runId=${runId ?: "all"}.")

    for (path in detailFiles) {
      path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val attempt = jsonConfiguration.decodeFromString<DeterminismReplayAttemptResult>(line)
        totalAttempts++
        sampledTickIds += attempt.tickId
        mutantTotals[attempt.mutantId] = (mutantTotals[attempt.mutantId] ?: 0) + 1

        outcomeCounts.bump(attempt.outcome)
        outcomeCountsBySampleSource
            .getOrPut(attempt.sampleSource) { mutableMapOf() }
            .bump(attempt.outcome)

        val bucket = LeadTimeBuckets.bucketLabel(attempt.leadTimeSeconds)
        leadTimeBucketOutcomeCounts.getOrPut(bucket) { mutableMapOf() }.bump(attempt.outcome)
        byMutant.getOrPut(attempt.mutantId) { mutableMapOf() }.bump(attempt.outcome)
        byMutantLeadBucket
            .getOrPut(attempt.mutantId) { mutableMapOf() }
            .getOrPut(bucket) { mutableMapOf() }
            .bump(attempt.outcome)

        if (attempt.outcome != ReplayAttemptOutcome.SUCCESS) {
          val examples = exampleTickIds.getOrPut(attempt.outcome) { mutableListOf() }
          if (examples.size < 10 && attempt.tickId !in examples) examples += attempt.tickId
        } else {
          attempt.maxAbsDiffPositionMeters?.let { positionDiffs += it }
          attempt.maxAbsDiffSpeedMps?.let { speedDiffs += it }
          attempt.maxAbsDiffAccelMps2?.let { accelDiffs += it }
        }
      }
    }

    val mutantStats =
        byMutant.entries
            .sortedBy { it.key }
            .map { (mutantId, counts) ->
              ReplayDeterminismMutantStats(
                  mutantId = mutantId,
                  totalAttempts = mutantTotals[mutantId] ?: 0,
                  outcomeCounts = counts,
                  leadTimeBucketOutcomeCounts = byMutantLeadBucket[mutantId] ?: emptyMap(),
              )
            }

    val summary =
        ReplayDeterminismSummary(
            runId = runId,
            totalAttempts = totalAttempts,
            totalTicksSampled = sampledTickIds.size,
            mutantsSampled = mutantTotals.size,
            outcomeCounts = outcomeCounts,
            outcomeCountsBySampleSource = outcomeCountsBySampleSource,
            leadTimeBucketOutcomeCounts = leadTimeBucketOutcomeCounts,
            mutantStats = mutantStats,
            exampleTickIdsByOutcome = exampleTickIds,
            epsilonHeadroom =
                mapOf(
                    "positionMeters" to epsilonHeadroomStats(positionDiffs),
                    "speedMps" to epsilonHeadroomStats(speedDiffs),
                    "accelMps2" to epsilonHeadroomStats(accelDiffs),
                ),
        )

    val base = basePath()
    Files.createDirectories(base)
    val summaryPath = base.resolve("replay_determinism_summary_${runId?.toString() ?: "all"}.json")
    summaryPath.writeText(jsonConfiguration.encodeToString(summary))
    println("Finished ReplayDeterminismAnalysis. Summary written to: $summaryPath")
    return summary
  }
}
