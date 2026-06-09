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

package tools.aqua.stars.coverage.significance.metrics

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageOutputStream
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.tsc.g0Accidents
import tools.aqua.stars.coverage.significance.tsc.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.tsc.g2EmergencyBraking
import tools.aqua.stars.coverage.significance.tsc.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.tsc.g4TrafficFlow
import tools.aqua.stars.coverage.significance.tsc.i1Stopping
import tools.aqua.stars.coverage.significance.tsc.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toReadableString
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toSetOfMonitorViolations
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G0Accidents
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G1SafeDistance
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G2EmergencyBraking
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.G4TrafficFlow
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I1Stopping
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.I2FasterThanLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolationBitmask
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

@Suppress("StringLiteralDuplication")
/**
 * Metric that tracks which monitors have failed for each TSC instance at each tick.
 *
 * @property dependsOn This metric does not depend on any other metric.
 * @property writeToDb Whether to write the results to the database.
 * @property writeVehicleStateImages Whether to write vehicle state images to the output folder.
 * @property vehicleStateImagesFolder The folder where vehicle state images will be written.
 * @property writeVehicleStateVideo Whether to write a video of the vehicle states to the output
 *   folder.
 * @property vehicleStateVideoFramesPerSecond The frame rate of the video.
 * @constructor Creates a new [FailedMonitorsPerTickMetric].
 */
class FailedMonitorsPerTickMetric(
    override val dependsOn: Any? = null,
    val writeToDb: Boolean = true,
    val writeVehicleStateImages: Boolean = false,
    val vehicleStateImagesFolder: Path =
        Path.of("analysis-result-logs", "failed-monitor-vehicle-states"),
    val writeVehicleStateVideo: Boolean = false,
    val vehicleStateVideoFramesPerSecond: Int = 2
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  private val monitorFailuresPerTick = mutableListOf<FailedMonitorsPerTick>()

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val failedMonitorInstances = tscInstance.rootNode.validateMonitors(tick.identifier)
    val setOfFailedMonitors = mutableSetOf<MonitorViolation>()
    failedMonitorInstances.forEach { violatedMonitor ->
      when (violatedMonitor.monitorLabel) {
        g0Accidents.name -> setOfFailedMonitors.add(G0Accidents)
        g1SafeDistanceToPrecedingVehicle.name -> setOfFailedMonitors.add(G1SafeDistance)
        g2EmergencyBraking.name -> setOfFailedMonitors.add(G2EmergencyBraking)
        g3MaximumSpeedLimit.name -> setOfFailedMonitors.add(G3MaximumSpeedLimit)
        g4TrafficFlow.name -> setOfFailedMonitors.add(G4TrafficFlow)
        i1Stopping.name -> setOfFailedMonitors.add(I1Stopping)
        i2DrivingFasterThenLeftTraffic.name -> setOfFailedMonitors.add(I2FasterThanLeftTraffic)
      }
    }

    val failedMonitorBitmask = setOfFailedMonitors.toBitmask()

    monitorFailuresPerTick.add(
        FailedMonitorsPerTick(
            tsc = tsc,
            tick = tick,
            failedMonitors = failedMonitorBitmask,
            tscInstance = tscInstance))
  }

  override fun postEvaluate() {}

  override fun printPostEvaluationResult() {
    val monitorFailuresPerTSC = monitorFailuresPerTick.groupBy { it.tsc }
    if (!writeToDb) {
      monitorFailuresPerTSC.forEach { (tsc, failedMonitors) ->
        val output = buildJoinedTimelineVisualization(tsc, failedMonitors)
        println(output)
        writeTextualMetricOutput(tsc, failedMonitors)
      }
      val analysis = buildMonitorViolationTSCInstanceAnalysis()
      println(analysis)
      writeMonitorViolationTSCInstanceAnalysis()
    }
  }

  /**
   * Creates a timeline where every tick is joined with a stable local ID for the TSC instance that
   * was active at that tick.
   */
  fun joinedTimeline(): List<TSCInstanceTimelineTick> {
    val instanceIds = globalTSCInstanceIds()

    return monitorFailuresPerTick
        .sortedWith(
            compareBy<FailedMonitorsPerTick> { it.tick.sourceIdentifier }
                .thenBy { it.tick.mutantId.toString() }
                .thenBy { it.tick.tickTimeMillis })
        .map { failure ->
          TSCInstanceTimelineTick(
              tsc = failure.tsc,
              tick = failure.tick,
              failedMonitors = failure.failedMonitors,
              tscInstance = failure.tscInstance,
              tscInstanceId = instanceIds.getValue(failure.tscInstance.getJsonString()))
        }
  }

  private fun globalTSCInstanceIds(): Map<String, String> =
      monitorFailuresPerTick
          .map { it.tscInstance.getJsonString() }
          .distinct()
          .sorted()
          .mapIndexed { index, instanceJson -> instanceJson to "Instance-${index + 1}" }
          .toMap()

  /**
   * Creates one row for every monitor violation tick, joined with the active TSC instance and the
   * TSC instance from the previous tick of the same mutant/scenario/TSC execution.
   */
  fun monitorViolationTSCInstanceTransitions(): List<MonitorViolationTSCInstanceTransition> =
      joinedTimeline()
          .groupBy {
            TimelineExecution(
                it.tsc, it.tick.scenarioConfigId.toString(), it.tick.mutantId.toString())
          }
          .flatMap { (_, ticksForExecution) ->
            val sortedTicks = ticksForExecution.sortedBy { it.tick.tickTimeMillis }
            sortedTicks.withIndex().flatMap { (index, tick) ->
              val previousTick = sortedTicks.getOrNull(index - 1)
              val lastChangedFromTickIndex =
                  (index - 1 downTo 0).firstOrNull {
                    sortedTicks[it].tscInstanceId != tick.tscInstanceId
                  }
              val lastChangedFromTick = lastChangedFromTickIndex?.let { sortedTicks[it] }
              val lastChangedToTick =
                  lastChangedFromTickIndex?.let { sortedTicks.getOrNull(it + 1) }
              tick.failedMonitors.toSetOfMonitorViolations().map { monitor ->
                MonitorViolationTSCInstanceTransition(
                    monitor = monitor,
                    sourceIdentifier = tick.tick.scenarioConfigId.toString(),
                    mutantId = tick.tick.mutantId.toString(),
                    fromTickMillis = previousTick?.tick?.tickTimeMillis,
                    toTickMillis = tick.tick.tickTimeMillis,
                    violatingTSCInstanceId = tick.tscInstanceId,
                    violatingTSCInstance = tick.tscInstance,
                    previousTSCInstanceId = previousTick?.tscInstanceId,
                    previousTSCInstance = previousTick?.tscInstance,
                    lastChangedFromTSCInstanceId = lastChangedFromTick?.tscInstanceId,
                    lastChangedFromTSCInstance = lastChangedFromTick?.tscInstance,
                    lastChangeFromTickMillis = lastChangedFromTick?.tick?.tickTimeMillis,
                    lastChangeToTickMillis = lastChangedToTick?.tick?.tickTimeMillis,
                )
              }
            }
          }

  /** Creates contiguous ranges for each TSC instance in the joined timeline. */
  fun tscInstanceRanges(): List<TSCInstanceTimelineRange> {
    val ranges = mutableListOf<TSCInstanceTimelineRange>()

    joinedTimeline()
        .groupBy {
          TimelineSource(it.tick.scenarioConfigId.toString(), it.tick.mutantId.toString())
        }
        .forEach { (_, ticksForSource) ->
          ticksForSource
              .groupBy { it.tsc }
              .forEach { (_, ticksForTSC) ->
                var currentRangeTicks = mutableListOf<TSCInstanceTimelineTick>()

                ticksForTSC
                    .sortedBy { it.tick.tickTimeMillis }
                    .forEach { timelineTick ->
                      val continuesCurrentRange =
                          currentRangeTicks.lastOrNull()?.tscInstanceId ==
                              timelineTick.tscInstanceId

                      if (currentRangeTicks.isNotEmpty() && !continuesCurrentRange) {
                        ranges += currentRangeTicks.toTimelineRange()
                        currentRangeTicks = mutableListOf()
                      }

                      currentRangeTicks += timelineTick
                    }

                if (currentRangeTicks.isNotEmpty()) {
                  ranges += currentRangeTicks.toTimelineRange()
                }
              }
        }

    return ranges
  }

  private fun buildJoinedTimelineVisualization(
      tsc: TSC<*, *, *, *>,
      failedMonitors: List<FailedMonitorsPerTick>,
      sourceFilter: TimelineSource? = null,
      includeArtifacts: Boolean = true
  ): String = buildString {
    val relevantTSCs = failedMonitors.map { it.tsc }.toSet()
    val relevantTicks = joinedTimeline().filter { it.tsc in relevantTSCs }
    val relevantRanges =
        tscInstanceRanges().filter { range ->
          range.tsc in relevantTSCs && relevantTicks.any { it in range.ticks }
        }

    appendLine("TSC:")
    appendLine(tsc)
    appendLine("")
    appendLine("----------------------------------------")
    appendLine("------Joined TSC Instance Timeline------")
    appendLine("----------------------------------------")

    relevantTicks
        .groupBy {
          TimelineSource(it.tick.scenarioConfigId.toString(), it.tick.mutantId.toString())
        }
        .filterKeys { sourceFilter == null || it == sourceFilter }
        .forEach { (source, ticksForSource) ->
          appendLine("Source: ${source.sourceIdentifier}, Mutant: ${source.mutantId}")
          appendLine("TSC instance legend:")
          ticksForSource
              .distinctBy { it.tscInstanceId }
              .sortedBy { it.tscInstanceId.counterValue() }
              .forEach { tick -> appendLine("${tick.tscInstanceId}: ${tick.tscInstance}") }

          appendLine("")
          appendLine("TSC instance ranges:")
          relevantRanges
              .filter { range ->
                range.sourceIdentifier == source.sourceIdentifier &&
                    range.mutantId == source.mutantId
              }
              .forEach { range ->
                appendLine(
                    "${range.tscInstanceId}: ${range.fromTickMillis}..${range.toTickMillis} ms " +
                        "(${range.ticks.size} ticks)")
              }

          appendLine("")
          appendLine("Failed monitors by tick:")
          appendLine("tick(ms) | tscInstance | failed monitors")
          ticksForSource
              .sortedBy { it.tick.tickTimeMillis }
              .forEach { tick ->
                appendLine(
                    "${tick.tick.tickTimeMillis.toString().padStart(8)} | " +
                        "${tick.tscInstanceId.padEnd(11)} | " +
                        tick.failedMonitors.toTimelineLabel())
              }

          appendLine("")
          appendLine("Vehicle states by tick:")
          ticksForSource
              .sortedBy { it.tick.tickTimeMillis }
              .forEach { tick -> appendLine(tick.toVehicleStateAscii()) }
          if (includeArtifacts && writeVehicleStateImages) {
            val outputFolder = outputFolderFor(source)
            val imagePaths =
                ticksForSource
                    .sortedBy { it.tick.tickTimeMillis }
                    .mapIndexed { index, tick -> tick.writeVehicleStateImage(outputFolder, index) }
            appendLine("Vehicle state images:")
            imagePaths.forEach { appendLine(it.toAbsolutePath()) }

            if (writeVehicleStateVideo) {
              val videoPath = writeVehicleStateVideo(imagePaths, source, outputFolder)
              appendLine("Vehicle state video: ${videoPath.toAbsolutePath()}")
            }
          }
          appendLine("----------------------------------------")
        }
  }

  private fun writeTextualMetricOutput(
      tsc: TSC<*, *, *, *>,
      failedMonitors: List<FailedMonitorsPerTick>,
  ) {
    failedMonitors
        .map { TimelineSource(it.tick.scenarioConfigId.toString(), it.tick.mutantId.toString()) }
        .distinct()
        .forEach { source ->
          val outputFolder = outputFolderFor(source)
          val sourceSpecificOutput =
              buildJoinedTimelineVisualization(
                  tsc = tsc,
                  failedMonitors = failedMonitors,
                  sourceFilter = source,
                  includeArtifacts = false)
          Files.createDirectories(outputFolder)
          outputFolder
              .resolve("failed-monitors-per-tick.txt")
              .toFile()
              .writeText(sourceSpecificOutput)
        }
  }

  private fun outputFolderFor(source: TimelineSource): Path =
      vehicleStateImagesFolder
          .resolve("mutant-${source.mutantId.sanitizeFilePart()}")
          .resolve("scenario-${source.sourceIdentifier.sanitizeFilePart()}")

  private fun buildMonitorViolationTSCInstanceAnalysis(): String = buildString {
    val transitions = monitorViolationTSCInstanceTransitions()
    appendLine("")
    appendLine("========================================")
    appendLine("TSC Instance Monitor Violation Analysis")
    appendLine("========================================")
    appendLine("Total monitor violation ticks: ${transitions.size}")
    appendLine("")

    MonitorViolation.entries.forEach { monitor ->
      val monitorTransitions = transitions.filter { it.monitor == monitor }
      if (monitorTransitions.isEmpty()) return@forEach

      append(buildMonitorViolationTSCInstanceAnalysisForMonitor(monitor, monitorTransitions))
    }
  }

  private fun buildMonitorViolationTSCInstanceAnalysisForMonitor(
      monitor: MonitorViolation,
      monitorTransitions: List<MonitorViolationTSCInstanceTransition>
  ): String = buildString {
    val violatingInstances = monitorTransitions.groupBy { it.violatingTSCInstanceId }
    val previousInstances = monitorTransitions.groupBy { it.previousTSCInstanceId ?: "none" }
    val lastChangedFromInstances =
        monitorTransitions.groupBy { it.lastChangedFromTSCInstanceId ?: "none" }
    val transitionCounts =
        monitorTransitions
            .groupingBy { (it.previousTSCInstanceId ?: "none") to it.violatingTSCInstanceId }
            .eachCount()
    val lastChangeTransitionCounts =
        monitorTransitions
            .groupingBy { (it.lastChangedFromTSCInstanceId ?: "none") to it.violatingTSCInstanceId }
            .eachCount()
    val sameInstanceTransitions =
        transitionCounts.filterKeys { (previousInstance, violatingInstance) ->
          previousInstance == violatingInstance
        }
    val changedInstanceTransitions =
        transitionCounts.filterKeys { (previousInstance, violatingInstance) ->
          previousInstance != violatingInstance
        }

    appendLine("Monitor: $monitor")
    appendLine("Violation ticks: ${monitorTransitions.size}")
    appendLine("Different violating TSC instances: ${violatingInstances.size}")
    appendLine("Different previous TSC instances: ${previousInstances.size}")
    appendLine("Different last-changed-from TSC instances: ${lastChangedFromInstances.size}")
    appendLine("")
    appendLine("Violating TSC instances:")
    violatingInstances.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<MonitorViolationTSCInstanceTransition>>> {
                  it.value.size
                }
                .thenBy { it.key.counterValue() })
        .forEach { (instanceId, entries) ->
          appendLine("- $instanceId: ${entries.size} violation ticks")
          appendLine(entries.first().violatingTSCInstance.toString().prependIndent("  "))
        }

    appendLine("")
    appendLine("TSC instances present one tick before violation:")
    previousInstances.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<MonitorViolationTSCInstanceTransition>>> {
                  it.value.size
                }
                .thenBy { it.key.counterValue() })
        .forEach { (instanceId, entries) ->
          appendLine("- $instanceId: ${entries.size} preceding ticks")
          val previousInstance = entries.first().previousTSCInstance
          if (previousInstance == null) {
            appendLine("  No previous tick in this execution.")
          } else {
            appendLine(previousInstance.toString().prependIndent("  "))
          }
        }

    appendLine("")
    appendLine("Transitions where the instance stayed the same:")
    appendTransitionCounts(sameInstanceTransitions, monitorTransitions)
    appendLine("")
    appendLine("Transitions where the instance changed:")
    appendTransitionCounts(changedInstanceTransitions, monitorTransitions)
    appendLine("")
    appendLine("TSC instances before the last change into the violating instance:")
    lastChangedFromInstances.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<MonitorViolationTSCInstanceTransition>>> {
                  it.value.size
                }
                .thenBy { it.key.counterValue() })
        .forEach { (instanceId, entries) ->
          appendLine("- $instanceId: ${entries.size} violation ticks")
          val lastChangedFromInstance = entries.first().lastChangedFromTSCInstance
          if (lastChangedFromInstance == null) {
            appendLine("  No earlier TSC instance change in this execution.")
          } else {
            appendLine(lastChangedFromInstance.toString().prependIndent("  "))
          }
        }
    appendLine("")
    appendLine("Last changed TSC instance transitions before violation:")
    appendLastChangeTransitionCounts(lastChangeTransitionCounts, monitorTransitions)
    appendLine("----------------------------------------")
    appendLine("")
  }

  private fun StringBuilder.appendTransitionCounts(
      transitionCounts: Map<Pair<String, String>, Int>,
      monitorTransitions: List<MonitorViolationTSCInstanceTransition>
  ) {
    if (transitionCounts.isEmpty()) {
      appendLine("- none")
      return
    }

    transitionCounts.entries
        .sortedWith(
            compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                .thenBy { it.key.first.counterValue() }
                .thenBy { it.key.second.counterValue() })
        .forEach { (transition, count) ->
          appendLine("- ${transition.first} -> ${transition.second}: $count violation ticks")
          monitorTransitions
              .filter {
                (it.previousTSCInstanceId ?: "none") == transition.first &&
                    it.violatingTSCInstanceId == transition.second
              }
              .sortedWith(
                  compareBy<MonitorViolationTSCInstanceTransition> { it.mutantId }
                      .thenBy { it.sourceIdentifier }
                      .thenBy { it.toTickMillis })
              .forEach { detail ->
                appendLine(
                    "  - mutant=${detail.mutantId}, scenario=${detail.sourceIdentifier}, " +
                        "fromTick=${detail.fromTickMillis?.let { "$it ms" } ?: "none"}, " +
                        "toTick=${detail.toTickMillis} ms")
              }
        }
  }

  private fun StringBuilder.appendLastChangeTransitionCounts(
      transitionCounts: Map<Pair<String, String>, Int>,
      monitorTransitions: List<MonitorViolationTSCInstanceTransition>
  ) {
    if (transitionCounts.isEmpty()) {
      appendLine("- none")
      return
    }

    transitionCounts.entries
        .sortedWith(
            compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                .thenBy { it.key.first.counterValue() }
                .thenBy { it.key.second.counterValue() })
        .forEach { (transition, count) ->
          appendLine("- ${transition.first} -> ${transition.second}: $count violation ticks")
          monitorTransitions
              .filter {
                (it.lastChangedFromTSCInstanceId ?: "none") == transition.first &&
                    it.violatingTSCInstanceId == transition.second
              }
              .sortedWith(
                  compareBy<MonitorViolationTSCInstanceTransition> { it.mutantId }
                      .thenBy { it.sourceIdentifier }
                      .thenBy { it.toTickMillis })
              .forEach { detail ->
                appendLine(
                    "  - mutant=${detail.mutantId}, scenario=${detail.sourceIdentifier}, " +
                        "changeFromTick=${detail.lastChangeFromTickMillis?.let { "$it ms" } ?: "none"}, " +
                        "changeToTick=${detail.lastChangeToTickMillis?.let { "$it ms" } ?: "none"}, " +
                        "violationTick=${detail.toTickMillis} ms")
              }
        }
  }

  private fun writeMonitorViolationTSCInstanceAnalysis() {
    val transitions = monitorViolationTSCInstanceTransitions()
    Files.createDirectories(vehicleStateImagesFolder)
    MonitorViolation.entries.forEach { monitor ->
      val monitorTransitions = transitions.filter { it.monitor == monitor }
      if (monitorTransitions.isEmpty()) return@forEach

      val analysis = buildMonitorViolationTSCInstanceAnalysisForMonitor(monitor, monitorTransitions)
      vehicleStateImagesFolder
          .resolve("tsc-instance-monitor-violation-analysis-${monitor.name.sanitizeFilePart()}.txt")
          .toFile()
          .writeText(analysis)
    }
  }

  private fun List<TSCInstanceTimelineTick>.toTimelineRange(): TSCInstanceTimelineRange =
      TSCInstanceTimelineRange(
          tsc = first().tsc,
          tscInstance = first().tscInstance,
          tscInstanceId = first().tscInstanceId,
          sourceIdentifier = first().tick.scenarioConfigId.toString(),
          mutantId = first().tick.mutantId.toString(),
          fromTickMillis = first().tick.tickTimeMillis,
          toTickMillis = last().tick.tickTimeMillis,
          ticks = this)

  private fun MonitorViolationBitmask.toTimelineLabel(): String = toReadableString().ifBlank { "-" }

  private fun MonitorViolationBitmask.toMonitorFailureLabels(): List<String> =
      toReadableString().takeIf { it.isNotBlank() }?.split(", ") ?: listOf("none")

  private fun String.counterValue(): Int = substringAfterLast("-").toIntOrNull() ?: Int.MAX_VALUE

  private fun TSCInstanceTimelineTick.toVehicleStateAscii(): String = buildString {
    appendLine(
        "tick ${tick.tickTimeMillis} ms | $tscInstanceId | failed: ${failedMonitors.toTimelineLabel()}")
    appendLine("TSC instance: $tscInstance")
    tick.vehiclesInTick
        .groupBy { it.currentLane }
        .toSortedMap(compareByDescending { it.laneIndex })
        .forEach { (lane, vehicles) ->
          val sortedVehicles = vehicles.sortedBy { it.frontBumperPositionOnLaneMeters }
          append("lane ${lane.laneIndex} (${lane.laneId}) | ")
          sortedVehicles.forEachIndexed { index, vehicle ->
            if (index > 0) {
              val previous = sortedVehicles[index - 1]
              val bumperGapMeters =
                  vehicle.backBumperPositionOnLaneMeters - previous.frontBumperPositionOnLaneMeters
              append("--gap=${bumperGapMeters.meters()}-->")
            }
            append(vehicle.toAsciiLabel(tick.ego.vehicleId))
          }
          appendLine()
        }
  }

  private fun Vehicle.toAsciiLabel(egoVehicleId: String): String {
    val egoMarker = if (vehicleId == egoVehicleId) "*" else ""
    val id = vehicleId.shortenVehicleId()
    return "[$egoMarker$id type=${vehicleType.typeId} p=${frontBumperPositionOnLaneMeters.meters()} " +
        "v=${speedMetersPerSecond.metersPerSecond()} a=${accelerationMetersPerSecondSquared.metersPerSecondSquared()}]"
  }

  private fun String.shortenVehicleId(): String =
      replace("vehicle_", "veh_").let {
        if (it.length <= 24) it else "${it.take(10)}..${it.takeLast(10)}"
      }

  private fun Float.meters(): String = formatOneDecimal(this, "m")

  private fun Float.metersPerSecond(): String = formatOneDecimal(this, "m/s")

  private fun Float.metersPerSecondSquared(): String = formatOneDecimal(this, "m/s2")

  private fun formatOneDecimal(value: Float, unit: String): String =
      String.format(Locale.US, "%.1f%s", value, unit)

  private fun TSCInstanceTimelineTick.writeVehicleStateImage(folder: Path, frameIndex: Int): Path {
    Files.createDirectories(folder)
    val fileName = "frame_${frameIndex.toString().padStart(5, '0')}.png"
    val path = folder.resolve(fileName)
    ImageIO.write(renderVehicleStateImage(), "png", path.toFile())
    return path
  }

  private fun writeVehicleStateVideo(
      imagePaths: List<Path>,
      source: TimelineSource,
      outputFolder: Path,
  ): Path {
    require(imagePaths.isNotEmpty()) { "Cannot create a video without vehicle-state images." }
    println("Write vehicle state video.")
    val videoBaseName = "vehicle-state-timeline"
    val mp4Path = outputFolder.resolve("$videoBaseName.mp4")
    val gifPath = outputFolder.resolve("$videoBaseName.gif")

    val ffmpegExecutable = findFfmpegExecutable()
    println("Found FFmpeg executable: $ffmpegExecutable")
    return if (ffmpegExecutable != null) {
      writeMp4WithFfmpeg(imagePaths.withHeldFinalFrame(), mp4Path, ffmpegExecutable)
    } else {
      writeAnimatedGif(imagePaths, gifPath)
    }
  }

  private fun findFfmpegExecutable(): String? {
    if (canRunFfmpeg("ffmpeg")) return "ffmpeg"

    val candidatePaths =
        listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let {
              Path.of(it, "Microsoft", "WinGet", "Links", "ffmpeg.exe")
            },
            System.getenv("LOCALAPPDATA")
                ?.let { Path.of(it, "Microsoft", "WinGet", "Packages") }
                ?.let { findFfmpegBelow(it, maxDepth = 5) },
            System.getenv("ProgramFiles")?.let { Path.of(it, "ffmpeg", "bin", "ffmpeg.exe") },
            System.getenv("ProgramFiles")?.let {
              Path.of(it, "DownloadHelper CoApp", "ffmpeg.exe")
            },
        )

    return candidatePaths.map { it.toAbsolutePath().toString() }.firstOrNull { canRunFfmpeg(it) }
  }

  private fun findFfmpegBelow(root: Path, maxDepth: Int): Path? {
    if (!Files.exists(root)) return null
    return Files.find(
            root,
            maxDepth,
            { path, _ -> path.fileName.toString().equals("ffmpeg.exe", ignoreCase = true) })
        .use { paths -> paths.findFirst().orElse(null) }
  }

  private fun canRunFfmpeg(command: String): Boolean =
      runCatching {
            ProcessBuilder(command, "-version")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
          }
          .getOrDefault(false)

  private fun writeMp4WithFfmpeg(
      imagePaths: List<Path>,
      outputPath: Path,
      ffmpegExecutable: String
  ): Path {
    Files.createDirectories(outputPath.parent)
    check(imagePaths.all { it.parent == outputPath.parent }) {
      "All frames must be in the same folder as the output video."
    }
    val process =
        ProcessBuilder(
                ffmpegExecutable,
                "-y",
                "-framerate",
                vehicleStateVideoFramesPerSecond.coerceAtLeast(1).toString(),
                "-i",
                "frame_%05d.png",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                outputPath.fileName.toString())
            .directory(outputPath.parent.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "ffmpeg failed while creating $outputPath" }

    return outputPath
  }

  private fun List<Path>.withHeldFinalFrame(): List<Path> {
    if (isEmpty()) return this

    val finalFrameHoldCount =
        (vehicleStateVideoFramesPerSecond.coerceAtLeast(1) * 2).coerceAtLeast(2)
    val lastFrame = last()
    val heldFrames =
        (0 until finalFrameHoldCount).map { holdIndex ->
          val heldFrame =
              lastFrame.parent.resolve(
                  "frame_${(size + holdIndex).toString().padStart(5, '0')}.png")
          Files.copy(lastFrame, heldFrame, StandardCopyOption.REPLACE_EXISTING)
          heldFrame
        }
    return this + heldFrames
  }

  private fun writeAnimatedGif(imagePaths: List<Path>, outputPath: Path): Path {
    Files.createDirectories(outputPath.parent)
    val firstImage = ImageIO.read(imagePaths.first().toFile())
    val writer = ImageIO.getImageWritersBySuffix("gif").asSequence().first()
    val delayMillis = 1000 / vehicleStateVideoFramesPerSecond.coerceAtLeast(1)

    FileImageOutputStream(outputPath.toFile()).use { output ->
      val gifWriter = GifSequenceWriter(writer, output, firstImage.type, delayMillis, loop = true)
      gifWriter.use {
        it.writeToSequence(firstImage)
        imagePaths.drop(1).forEach { path -> it.writeToSequence(ImageIO.read(path.toFile())) }
      }
    }

    return outputPath
  }

  private fun TSCInstanceTimelineTick.renderVehicleStateImage(): BufferedImage {
    val laneGroups =
        tick.vehiclesInTick
            .groupBy { it.currentLane }
            .toSortedMap(compareByDescending { it.laneIndex })
    val width = 1600
    val headerHeight = (230 + tscInstance.toString().lines().size * 16).coerceAtMost(560)
    val laneHeight = 120
    val bottomPadding = 40
    val height = headerHeight + laneGroups.size.coerceAtLeast(1) * laneHeight + bottomPadding
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()

    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      graphics.color = Color(250, 250, 250)
      graphics.fillRect(0, 0, width, height)
      graphics.color = Color(25, 28, 32)
      graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
      graphics.drawString("Tick ${tick.tickTimeMillis} ms", 30, 40)
      graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
      graphics.drawString("Mutant: ${tick.mutantId} | Scenario: ${tick.scenarioConfigId}", 30, 62)

      graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
      graphics.drawWrappedMultilineString(
          "$tscInstanceId:\n$tscInstance", x = 30, y = 88, maxWidth = 980)

      graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
      graphics.drawString("Found monitor violations", 1060, 88)
      graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
      graphics.drawBulletList(
          failedMonitors.toMonitorFailureLabels(), x = 1060, y = 114, maxWidth = width - 1090)

      graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
      graphics.drawString(
          "Ego vehicle is highlighted in green. Positions are front bumper positions.",
          30,
          headerHeight - 25)

      val allVehicles = tick.vehiclesInTick
      val minBack =
          allVehicles.minOfOrNull { vehicle -> vehicle.backBumperPositionOnLaneMeters } ?: 0.0f
      val maxFront =
          allVehicles.maxOfOrNull { vehicle -> vehicle.frontBumperPositionOnLaneMeters } ?: 1.0f
      val visibleStart = (minBack - 15.0f).coerceAtMost(0.0f)
      val visibleEnd = (maxFront + 15.0f).coerceAtLeast(visibleStart + 1.0f)

      laneGroups.entries.forEachIndexed { index, (lane, vehicles) ->
        val y = headerHeight + index * laneHeight
        val laneTop = y + 28
        val laneCenter = laneTop + 40
        val roadLeft = 150
        val roadRight = width - 50

        graphics.color = Color(35, 40, 45)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        graphics.drawString("Lane ${lane.laneIndex} (${lane.laneId})", 30, laneCenter + 5)

        graphics.color = Color(218, 222, 226)
        graphics.fillRoundRect(roadLeft, laneTop, roadRight - roadLeft, 80, 8, 8)
        graphics.color = Color(130, 136, 142)
        graphics.stroke = BasicStroke(2f)
        graphics.drawLine(roadLeft, laneCenter, roadRight, laneCenter)

        vehicles
            .sortedBy { vehicle -> vehicle.frontBumperPositionOnLaneMeters }
            .forEach { vehicle ->
              graphics.drawVehicle(
                  vehicle,
                  tick.ego.vehicleId,
                  visibleStart,
                  visibleEnd,
                  roadLeft,
                  roadRight,
                  laneTop)
            }
      }
    } finally {
      graphics.dispose()
    }

    return image
  }

  private fun Graphics2D.drawVehicle(
      vehicle: Vehicle,
      egoVehicleId: String,
      visibleStart: Float,
      visibleEnd: Float,
      roadLeft: Int,
      roadRight: Int,
      laneTop: Int
  ) {
    val vehicleLeft =
        vehicle.backBumperPositionOnLaneMeters.toImageX(
            visibleStart, visibleEnd, roadLeft, roadRight)
    val vehicleRight =
        vehicle.frontBumperPositionOnLaneMeters.toImageX(
            visibleStart, visibleEnd, roadLeft, roadRight)
    val bodyWidth = (vehicleRight - vehicleLeft).coerceAtLeast(2)
    val bodyHeight = 30
    val bodyY = laneTop + 25
    val isEgo = vehicle.vehicleId == egoVehicleId

    color = if (isEgo) Color(48, 145, 89) else vehicle.vehicleType.typeId.toVehicleColor()
    fillRoundRect(vehicleLeft, bodyY, bodyWidth, bodyHeight, 6, 6)
    color = Color(20, 24, 28)
    stroke = BasicStroke(1.5f)
    drawRoundRect(vehicleLeft, bodyY, bodyWidth, bodyHeight, 6, 6)

    val label =
        "${if (isEgo) "*" else ""}${vehicle.vehicleId.shortenVehicleId()} ${vehicle.vehicleType.typeId}"
    font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    color = Color(20, 24, 28)
    drawString(label, vehicleLeft, bodyY - 6)

    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    drawString(
        "p=${vehicle.frontBumperPositionOnLaneMeters.meters()} v=${vehicle.speedMetersPerSecond.metersPerSecond()} a=${vehicle.accelerationMetersPerSecondSquared.metersPerSecondSquared()}",
        vehicleLeft,
        bodyY + bodyHeight + 18)
  }

  private fun Float.toImageX(
      visibleStart: Float,
      visibleEnd: Float,
      roadLeft: Int,
      roadRight: Int
  ): Int {
    val ratio = ((this - visibleStart) / (visibleEnd - visibleStart)).coerceIn(0.0f, 1.0f)
    return roadLeft + ((roadRight - roadLeft) * ratio).toInt()
  }

  private fun Graphics2D.drawWrappedMultilineString(
      text: String,
      x: Int,
      y: Int,
      maxWidth: Int
  ): Int {
    var currentY = y
    text.lines().forEach { rawLine ->
      val expandedLine = rawLine.replace("\t", "  ")
      val indentation = expandedLine.takeWhile { it == ' ' }
      val lineContent = expandedLine.drop(indentation.length)
      val indentationWidth = fontMetrics.stringWidth(indentation)
      val words = lineContent.split(Regex("\\s+")).filter { it.isNotBlank() }
      if (words.isEmpty()) {
        if (indentation.isNotEmpty()) drawString(indentation, x, currentY)
        currentY += fontMetrics.height
      } else {
        var line = ""
        words.forEach { word ->
          val candidate = if (line.isBlank()) word else "$line $word"
          if (indentationWidth + fontMetrics.stringWidth(candidate) > maxWidth &&
              line.isNotBlank()) {
            drawString(indentation + line, x, currentY)
            line = word
            currentY += fontMetrics.height
          } else {
            line = candidate
          }
        }
        if (line.isNotBlank()) {
          drawString(indentation + line, x, currentY)
          currentY += fontMetrics.height
        }
      }
    }

    return currentY
  }

  private fun Graphics2D.drawBulletList(items: List<String>, x: Int, y: Int, maxWidth: Int): Int {
    var currentY = y
    items.forEach { item ->
      val bulletPrefix = "- "
      val words = item.split(Regex("\\s+")).filter { it.isNotBlank() }
      var line = ""

      words.forEach { word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (fontMetrics.stringWidth(bulletPrefix + candidate) > maxWidth && line.isNotBlank()) {
          drawString(bulletPrefix + line, x, currentY)
          line = word
          currentY += fontMetrics.height
        } else {
          line = candidate
        }
      }

      drawString(bulletPrefix + line.ifBlank { item }, x, currentY)
      currentY += fontMetrics.height
    }

    return currentY
  }

  private fun String.toVehicleColor(): Color =
      when {
        contains("truck", ignoreCase = true) -> Color(168, 112, 56)
        contains("sport", ignoreCase = true) -> Color(194, 79, 67)
        contains("calm", ignoreCase = true) -> Color(86, 132, 190)
        contains("normal", ignoreCase = true) -> Color(226, 184, 72)
        else -> Color(126, 119, 166)
      }

  private fun String.sanitizeFilePart(): String =
      replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "unknown" }
}

/**
 * Represents the failed monitors for a specific tick within the context of a [TSC] evaluation.
 * *
 *
 * @property tsc The [TSC] instance representing the tree structure being evaluated.
 * @property tick The [TimeStep] instance representing the simulation data for the specific tick.
 * @property failedMonitors A bitmask ([MonitorViolationBitmask]) indicating the monitors that
 *   failed during the evaluation for the given tick.
 * @property tscInstance The [TSCInstance] derived from the [TSC] evaluation for the specified tick.
 */
data class FailedMonitorsPerTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>
)

/**
 * Represents the result of a [TSC] evaluation.
 *
 * @property tsc The [TSC] instance used for the evaluation.
 * @property tick The [TimeStep] instance representing the simulation data for the evaluated tick.
 * @property failedMonitors A bitmask ([MonitorViolationBitmask]) indicating the monitors that
 *   failed during the evaluation for the given tick.
 * @property tscInstance The [TSCInstance] derived from the [TSC] evaluation for the specified tick.
 * @property tscInstanceId The unique identifier for the [TSCInstance] derived from the [TSC]
 *   evaluation.
 */
data class TSCInstanceTimelineTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>,
    val tscInstanceId: String
)

/**
 * Represents a specific range in the timeline of a [TSCInstance].
 *
 * @property tsc The [TSC] used to generate this timeline range.
 * @property tscInstance The specific [TSCInstance] associated with this timeline range.
 * @property tscInstanceId The unique identifier of the [TSCInstance] for this range.
 * @property sourceIdentifier A string identifier indicating the source of the evaluation.
 * @property mutantId A unique identifier of the mutant entity related to this range.
 * @property fromTickMillis The starting time (inclusive) of the timeline range in milliseconds.
 * @property toTickMillis The ending time (inclusive) of the timeline range in milliseconds.
 * @property ticks The list of [TSCInstanceTimelineTick] objects representing individual tick
 *   evaluations within this range.
 */
data class TSCInstanceTimelineRange(
    val tsc: TSC<*, *, *, *>,
    val tscInstance: TSCInstance<*, *, *, *>,
    val tscInstanceId: String,
    val sourceIdentifier: String,
    val mutantId: String,
    val fromTickMillis: Long,
    val toTickMillis: Long,
    val ticks: List<TSCInstanceTimelineTick>
)

/**
 * Represents a source of timeline data with a unique identifier and associated mutant ID.
 *
 * @property sourceIdentifier A unique identifier for the timeline source.
 * @property mutantId The ID associated with the mutant in the timeline source.
 */
private data class TimelineSource(val sourceIdentifier: String, val mutantId: String)

/**
 * Represents the execution of a timeline associated with a specific TSC instance, identified by a
 * source identifier and a mutant ID.
 *
 * @property tsc The TSC instance that represents the tree structure being executed.
 * @property sourceIdentifier A unique identifier associated with the source of the timeline
 *   execution.
 * @property mutantId A unique identifier used to track mutations or changes within the timeline
 *   context.
 */
private data class TimelineExecution(
    val tsc: TSC<*, *, *, *>,
    val sourceIdentifier: String,
    val mutantId: String
)

/**
 * Represents a transition of instances in a Temporal Structure Chart (TSC) result that violates a
 * specific monitor condition.
 *
 * @property monitor The specific monitor violation associated with this transition.
 * @property sourceIdentifier The unique identifier of the source from which the transition
 *   originated.
 * @property mutantId The unique identifier of the mutant associated with this transition.
 * @property fromTickMillis The timestamp in milliseconds representing the starting point of this
 *   transition, if any.
 * @property toTickMillis The timestamp in milliseconds representing the ending point of this
 *   transition.
 * @property violatingTSCInstanceId The unique identifier of the TSC instance that caused the
 *   violation.
 * @property violatingTSCInstance The TSC instance that caused the violation.
 * @property previousTSCInstanceId The unique identifier of the previous TSC instance, if any.
 * @property previousTSCInstance The TSC instance observed prior to the violation, if any.
 * @property lastChangedFromTSCInstanceId The unique identifier of the last TSC instance that
 *   initiated the change leading to this violation, if any.
 * @property lastChangedFromTSCInstance The last TSC instance that initiated the change leading to
 *   this violation, if any.
 * @property lastChangeFromTickMillis The timestamp in milliseconds representing the starting point
 *   of the last change, if any.
 * @property lastChangeToTickMillis The timestamp in milliseconds representing the ending point of
 *   the last change, if any.
 */
data class MonitorViolationTSCInstanceTransition(
    val monitor: MonitorViolation,
    val sourceIdentifier: String,
    val mutantId: String,
    val fromTickMillis: Long?,
    val toTickMillis: Long,
    val violatingTSCInstanceId: String,
    val violatingTSCInstance: TSCInstance<*, *, *, *>,
    val previousTSCInstanceId: String?,
    val previousTSCInstance: TSCInstance<*, *, *, *>?,
    val lastChangedFromTSCInstanceId: String?,
    val lastChangedFromTSCInstance: TSCInstance<*, *, *, *>?,
    val lastChangeFromTickMillis: Long?,
    val lastChangeToTickMillis: Long?,
)

/**
 * A utility class for writing animated GIFs by adding images to a sequence.
 *
 * This class handles the creation of GIF metadata and sequences for animated GIFs. It provides
 * methods for setting up the GIF writer, writing frames, and closing the sequence.
 *
 * @param writer The `ImageWriter` instance used to write the GIF.
 * @param output The `ImageOutputStream` where the GIF data is written.
 * @param imageType The type of images to be written, specified using constants from
 *   `BufferedImage`.
 * @param delayMillis The delay time between frames in milliseconds.
 * @param loop Determines whether the GIF should loop indefinitely (`true`) or play once (`false`).
 */
private class GifSequenceWriter(
    private val writer: ImageWriter,
    private val output: ImageOutputStream,
    imageType: Int,
    delayMillis: Int,
    loop: Boolean
) : AutoCloseable {
  private val metadata: IIOMetadata =
      writer.getDefaultImageMetadata(
          ImageTypeSpecifier.createFromBufferedImageType(imageType), null)

  init {
    val root = metadata.getAsTree(GIF_METADATA_FORMAT) as IIOMetadataNode

    val graphicsControlExtensionNode = root.getOrCreateChild("GraphicControlExtension")
    graphicsControlExtensionNode.setAttribute("disposalMethod", "none")
    graphicsControlExtensionNode.setAttribute("userInputFlag", "FALSE")
    graphicsControlExtensionNode.setAttribute("transparentColorFlag", "FALSE")
    graphicsControlExtensionNode.setAttribute(
        "delayTime", (delayMillis / 10).coerceAtLeast(1).toString())
    graphicsControlExtensionNode.setAttribute("transparentColorIndex", "0")

    val applicationExtensionsNode = root.getOrCreateChild("ApplicationExtensions")
    val applicationExtensionNode = IIOMetadataNode("ApplicationExtension")
    applicationExtensionNode.setAttribute("applicationID", "NETSCAPE")
    applicationExtensionNode.setAttribute("authenticationCode", "2.0")
    val loopCount = if (loop) 0 else 1
    applicationExtensionNode.userObject =
        byteArrayOf(0x1, (loopCount and 0xFF).toByte(), ((loopCount shr 8) and 0xFF).toByte())
    applicationExtensionsNode.appendChild(applicationExtensionNode)

    metadata.setFromTree(GIF_METADATA_FORMAT, root)
    writer.output = output
    writer.prepareWriteSequence(null)
  }

  /**
   * Writes a single image to a sequence in the output stream managed by this writer.
   *
   * @param image the BufferedImage to be written to the sequence
   */
  fun writeToSequence(image: BufferedImage) {
    writer.writeToSequence(javax.imageio.IIOImage(image, null, metadata), null)
  }

  override fun close() {
    writer.endWriteSequence()
    writer.dispose()
  }

  private fun IIOMetadataNode.getOrCreateChild(name: String): IIOMetadataNode {
    val nodes = getElementsByTagName(name)
    if (nodes.length > 0) return nodes.item(0) as IIOMetadataNode
    val node = IIOMetadataNode(name)
    appendChild(node)
    return node
  }

  private companion object {
    const val GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0"
  }
}
