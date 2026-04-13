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
import tools.aqua.stars.coverage.significance.g0Accidents
import tools.aqua.stars.coverage.significance.g1SafeDistanceToPrecedingVehicle
import tools.aqua.stars.coverage.significance.g2EmergencyBraking
import tools.aqua.stars.coverage.significance.g3MaximumSpeedLimit
import tools.aqua.stars.coverage.significance.g4TrafficFlow
import tools.aqua.stars.coverage.significance.i1Stopping
import tools.aqua.stars.coverage.significance.i2DrivingFasterThenLeftTraffic
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toBitmask
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.toReadableString
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

/**
 * Metric that tracks which monitors have failed for each TSC instance at each tick.
 *
 * @property dependsOn This metric does not depend on any other metric.
 * @property writeToDb Whether to write the results to the database.
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
        println("TSC:\n $tsc")
        printJoinedTimelineVisualization(failedMonitors)
      }
    }
  }

  /**
   * Creates a timeline where every tick is joined with a stable local ID for the TSC instance that
   * was active at that tick.
   */
  fun joinedTimeline(): List<TSCInstanceTimelineTick> {
    val instanceIds =
        monitorFailuresPerTick
            .sortedWith(
                compareBy<FailedMonitorsPerTick> { it.tick.sourceIdentifier }
                    .thenBy { it.tick.mutantId.toString() }
                    .thenBy { it.tick.tickTimeMillis })
            .distinctBy { it.tscInstance.getJsonString() }
            .mapIndexed { index, failure ->
              failure.tscInstance.getJsonString() to "Instance-${index + 1}"
            }
            .toMap()

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

  /** Creates contiguous ranges for each TSC instance in the joined timeline. */
  fun tscInstanceRanges(): List<TSCInstanceTimelineRange> {
    val ranges = mutableListOf<TSCInstanceTimelineRange>()

    joinedTimeline()
        .groupBy { TimelineSource(it.tick.sourceIdentifier, it.tick.mutantId.toString()) }
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

  private fun printJoinedTimelineVisualization(failedMonitors: List<FailedMonitorsPerTick>) {
    val relevantTSCs = failedMonitors.map { it.tsc }.toSet()
    val relevantTicks = joinedTimeline().filter { it.tsc in relevantTSCs }
    val relevantRanges =
        tscInstanceRanges().filter { range ->
          range.tsc in relevantTSCs && relevantTicks.any { it in range.ticks }
        }

    println("")
    println("----------------------------------------")
    println("------Joined TSC Instance Timeline------")
    println("----------------------------------------")

    relevantTicks
        .groupBy { TimelineSource(it.tick.sourceIdentifier, it.tick.mutantId.toString()) }
        .forEach { (source, ticksForSource) ->
          println("Source: ${source.sourceIdentifier}, Mutant: ${source.mutantId}")
          println("TSC instance legend:")
          ticksForSource
              .distinctBy { it.tscInstanceId }
              .sortedBy { it.tscInstanceId.counterValue() }
              .forEach { tick -> println("${tick.tscInstanceId}: ${tick.tscInstance}") }

          println("")
          println("TSC instance ranges:")
          relevantRanges
              .filter { range ->
                range.sourceIdentifier == source.sourceIdentifier &&
                    range.mutantId == source.mutantId
              }
              .forEach { range ->
                println(
                    "${range.tscInstanceId}: ${range.fromTickMillis}..${range.toTickMillis} ms " +
                        "(${range.ticks.size} ticks)")
              }

          println("")
          println("Failed monitors by tick:")
          println("tick(ms) | tscInstance | failed monitors")
          ticksForSource
              .sortedBy { it.tick.tickTimeMillis }
              .forEach { tick ->
                println(
                    "${tick.tick.tickTimeMillis.toString().padStart(8)} | " +
                        "${tick.tscInstanceId.padEnd(11)} | " +
                        tick.failedMonitors.toTimelineLabel())
              }

          println("")
          println("Vehicle states by tick:")
          ticksForSource
              .sortedBy { it.tick.tickTimeMillis }
              .forEach { tick -> println(tick.toVehicleStateAscii()) }
          if (writeVehicleStateImages) {
            val imagePaths =
                ticksForSource
                    .sortedBy { it.tick.tickTimeMillis }
                    .map { tick -> tick.writeVehicleStateImage(vehicleStateImagesFolder) }
            println("Vehicle state images:")
            imagePaths.forEach { println(it.toAbsolutePath()) }

            if (writeVehicleStateVideo) {
              val videoPath = writeVehicleStateVideo(imagePaths, source)
              println("Vehicle state video: ${videoPath.toAbsolutePath()}")
            }
          }
          println("----------------------------------------")
        }
  }

  private fun List<TSCInstanceTimelineTick>.toTimelineRange(): TSCInstanceTimelineRange =
      TSCInstanceTimelineRange(
          tsc = first().tsc,
          tscInstance = first().tscInstance,
          tscInstanceId = first().tscInstanceId,
          sourceIdentifier = first().tick.sourceIdentifier,
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

  private fun TSCInstanceTimelineTick.writeVehicleStateImage(folder: Path): Path {
    Files.createDirectories(folder)
    val fileName =
        listOf(
                tick.sourceIdentifier.sanitizeFilePart(),
                tick.mutantId.toString().sanitizeFilePart(),
                "${tick.tickTimeMillis}ms",
                tscInstanceId.sanitizeFilePart())
            .joinToString("_") + ".png"
    val path = folder.resolve(fileName)
    ImageIO.write(renderVehicleStateImage(), "png", path.toFile())
    return path
  }

  private fun writeVehicleStateVideo(
      imagePaths: List<Path>,
      source: TimelineSource,
  ): Path {
    require(imagePaths.isNotEmpty()) { "Cannot create a video without vehicle-state images." }
    println("Write vehicle state video.")
    val videoBaseName =
        "${source.sourceIdentifier.sanitizeFilePart()}_${source.mutantId.sanitizeFilePart()}"
    val mp4Path = vehicleStateImagesFolder.resolve("$videoBaseName.mp4")
    val gifPath = vehicleStateImagesFolder.resolve("$videoBaseName.gif")

    val ffmpegExecutable = findFfmpegExecutable()
    println("Found FFmpeg executable: $ffmpegExecutable")
    return if (ffmpegExecutable != null) {
      writeMp4WithFfmpeg(imagePaths, mp4Path, ffmpegExecutable)
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
    val frameList = outputPath.parent.resolve("${outputPath.fileName}.frames.txt")
    val frameDurationSeconds = 1.0 / vehicleStateVideoFramesPerSecond.coerceAtLeast(1)
    Files.writeString(
        frameList,
        imagePaths.joinToString(System.lineSeparator()) { path ->
          "file '${path.toAbsolutePath().toString().replace("'", "'\\''")}'" +
              System.lineSeparator() +
              "duration ${String.format(Locale.US, "%.3f", frameDurationSeconds)}"
        } + System.lineSeparator() + "file '${imagePaths.last().toAbsolutePath()}'")

    val process =
        ProcessBuilder(
                ffmpegExecutable,
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                frameList.toAbsolutePath().toString(),
                "-pix_fmt",
                "yuv420p",
                outputPath.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "ffmpeg failed while creating $outputPath" }

    return outputPath
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

      graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
      graphics.drawWrappedMultilineString(
          "$tscInstanceId:\n$tscInstance", x = 30, y = 68, maxWidth = 980)

      graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
      graphics.drawString("Found monitor violations", 1060, 68)
      graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
      graphics.drawBulletList(
          failedMonitors.toMonitorFailureLabels(), x = 1060, y = 94, maxWidth = width - 1090)

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
    val bodyWidth = (vehicleRight - vehicleLeft).coerceAtLeast(18)
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

data class FailedMonitorsPerTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>
)

data class TSCInstanceTimelineTick(
    val tsc: TSC<*, *, *, *>,
    val tick: TimeStep,
    val failedMonitors: MonitorViolationBitmask,
    val tscInstance: TSCInstance<*, *, *, *>,
    val tscInstanceId: String
)

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

private data class TimelineSource(val sourceIdentifier: String, val mutantId: String)

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
