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

package tools.aqua.stars.coverage.significance.gridTrafficGenerator

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.SEED
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.CALM
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.EGO
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.NORMAL
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.SPEEDY
import tools.aqua.stars.coverage.significance.gridTrafficGenerator.GridVehicleType.SPORTY
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO
import tools.aqua.stars.coverage.significance.tsc.VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM
import tools.aqua.stars.coverage.significance.utils.CliArgs

/**
 * Speed-grid scenario generator (`docs/v2-scenarios-and-mutants-plan.md`, section 4).
 *
 * The grid has three rows (behind, beside and ahead of the ego, see [i0Start] to [i2End]) and three
 * lanes. The ego sits in the middle row at the centre of the middle interval. A configuration fixes
 * the ego lane, the ego speed and the content of the other eight cells: empty, or a vehicle with
 * one of [otherSpeedsKmh], whose type follows from [typeBySpeedKmh]. All configurations are
 * enumerated, and each one is drawn [positionDraws] times with different positions inside the
 * cells.
 *
 * Enumeration index of a scenario: `draw * configurationCount + configuration`, with `configuration
 * = (egoSpeedIndex * 3 + egoLane) * patternCount + pattern`. Each of the eight other cells, in
 * [cellIndex] order, is one base-(1 + S) digit of `pattern`, with S the number of [otherSpeedsKmh]:
 * 0 for an empty cell and k for the k-th speed.
 *
 * Positions are drawn uniformly inside the cells and rounded to 0.1 m. On every lane, consecutive
 * vehicles (the ego included) must start with a bumper gap of at least [minStartGapMeters] and a
 * time to collision of at least [minStartTtcSeconds]; otherwise the lane is drawn again. Without
 * [variance], every vehicle stands at its cell centre and the start conditions are not applied.
 *
 * @property egoSpeedsKmh Start speeds of the ego in km/h.
 * @property otherSpeedsKmh Start speeds of the other vehicles in km/h.
 * @property typeBySpeedKmh Vehicle type (driver profile) of another vehicle, by its start speed.
 * @property positionDraws Number of position draws per configuration.
 * @property variance Whether positions are drawn inside the cells instead of using the cell
 *   centres.
 * @property minStartGapMeters Minimum bumper gap between consecutive vehicles on a lane.
 * @property minStartTtcSeconds Minimum time to collision between consecutive vehicles on a lane.
 * @property vehicleLengthMeters Vehicle length used for the bumper gap.
 * @property maxAttempts Maximum number of draws of a lane, and of a whole position draw that
 *   differs from the earlier draws of its configuration, before generation fails.
 * @property seed Seed of the position draws.
 * @property i0Start Start of the row behind the ego (meters).
 * @property i0End End of the row behind the ego (meters).
 * @property i1Start Start of the row beside the ego (meters).
 * @property i1End End of the row beside the ego (meters).
 * @property i2Start Start of the row ahead of the ego (meters).
 * @property i2End End of the row ahead of the ego (meters).
 */
data class GridTrafficScenarioGenerator(
    val egoSpeedsKmh: List<Int> = listOf(80, 100, 120),
    val otherSpeedsKmh: List<Int> = listOf(70, 90, 110, 130),
    val typeBySpeedKmh: Map<Int, GridVehicleType> =
        mapOf(70 to CALM, 90 to NORMAL, 110 to SPORTY, 130 to SPEEDY),
    val positionDraws: Int = 1,
    val variance: Boolean = true,
    val minStartGapMeters: Double = 10.0,
    val minStartTtcSeconds: Double = 4.0,
    val vehicleLengthMeters: Double = 5.0,
    val maxAttempts: Int = 1000,
    val seed: Int = SEED,
    val i0Start: Float = 0.0f,
    val i0End: Float = VEHICLE_IN_BEHIND_MAX_DISTANCE_METERS_TO,
    val i1Start: Float = i0End,
    val i1End: Float =
        i1Start +
            VEHICLE_IN_BEHIND_MIN_DISTANCE_METERS_FROM +
            VEHICLE_IN_FRONT_MIN_DISTANCE_METERS_FROM,
    val i2Start: Float = i1End,
    val i2End: Float = i2Start + VEHICLE_IN_FRONT_MAX_DISTANCE_METERS_TO,
) {
  init {
    requireDistinctPositive(egoSpeedsKmh, "egoSpeedsKmh")
    requireDistinctPositive(otherSpeedsKmh, "otherSpeedsKmh")
    val untyped = otherSpeedsKmh.filter { it !in typeBySpeedKmh }
    require(untyped.isEmpty()) { "typeBySpeedKmh has no type for the speeds $untyped" }
    require(EGO !in typeBySpeedKmh.values) { "typeBySpeedKmh must not map a speed to EGO" }
    require(positionDraws >= 1) { "positionDraws must be at least 1 but was $positionDraws" }
    require(variance || positionDraws == 1) {
      "Without variance every position draw is the same, so positionDraws must be 1"
    }
    require(minStartGapMeters >= 0.0 && minStartTtcSeconds >= 0.0 && vehicleLengthMeters >= 0.0) {
      "minStartGapMeters, minStartTtcSeconds and vehicleLengthMeters must not be negative"
    }
    require(maxAttempts >= 1) { "maxAttempts must be at least 1 but was $maxAttempts" }
    require(i0End > i0Start && i1End > i1Start && i2End > i2Start) {
      "Every row interval must have a positive length"
    }
  }

  private val rowIntervals =
      listOf(Interval(i0Start, i0End), Interval(i1Start, i1End), Interval(i2Start, i2End))

  private val egoPositionMeters: Float = rowIntervals[MIDDLE_ROW].center()

  private val statesPerCell: Int = otherSpeedsKmh.size + 1

  /** Number of cell patterns per ego lane and ego speed, `(1 + S)^8`. */
  val patternCount: Long = power(statesPerCell, CELL_COUNT - 1)

  /** Number of configurations, `3 * E * (1 + S)^8` with E ego speeds. */
  val configurationCount: Long = GRID_SIZE.toLong() * egoSpeedsKmh.size * patternCount

  /** Number of enumeration indices, [configurationCount] times [positionDraws]. */
  val indexCount: Long = configurationCount * positionDraws

  /**
   * Number of configurations without other vehicles. Their position draw is always the same, so
   * they exist only in draw 0.
   */
  private val egoOnlyConfigurationCount: Long = GRID_SIZE.toLong() * egoSpeedsKmh.size

  /** Number of generated scenarios: every index except later draws of ego-only configurations. */
  val scenarioCount: Long = scenariosBefore(indexCount)

  /**
   * Returns the number of scenarios whose enumeration index is below [index].
   *
   * @param index Enumeration index in `0..indexCount`.
   * @return Number of scenarios with a smaller enumeration index.
   */
  fun scenariosBefore(index: Long): Long {
    require(index in 0..indexCount) { "index $index outside 0..$indexCount" }
    val draw = index / configurationCount
    val configuration = index % configurationCount
    val skippedInCompleteDraws = maxOf(0L, draw - 1) * egoOnlyConfigurationCount
    val skippedInCurrentDraw =
        if (draw == 0L) 0L
        else minOf(egoOnlyConfigurationCount, (configuration + patternCount - 1) / patternCount)
    return index - skippedInCompleteDraws - skippedInCurrentDraw
  }

  /**
   * Returns the scenario with the given enumeration [index], or `null` if [index] is a later draw
   * of a configuration without other vehicles.
   *
   * @param index Enumeration index in `0 until indexCount`.
   * @param statistics Collects the position draws, if given.
   * @return The scenario, or `null`.
   */
  fun scenarioAt(index: Long, statistics: DrawStatistics? = null): GeneratedScenario? {
    require(index in 0 until indexCount) { "index $index outside 0 until $indexCount" }
    val draw = (index / configurationCount).toInt()
    val configuration = configurationAt(index % configurationCount)
    if (draw > 0 && configuration.speedByCell.isEmpty()) return null

    val positions = positionsOf(configuration, draw, statistics)
    val spawns = buildList {
      add(
          Spawn(
              row = MIDDLE_ROW,
              lane = configuration.egoLane,
              positionMeters = egoPositionMeters,
              type = EGO,
              speedKmh = configuration.egoSpeedKmh))
      configuration.speedByCell.forEach { (cell, speedKmh) ->
        add(
            Spawn(
                row = cell / GRID_SIZE,
                lane = cell % GRID_SIZE,
                positionMeters = positions.getValue(cell),
                type = typeBySpeedKmh.getValue(speedKmh),
                speedKmh = speedKmh))
      }
    }
    return GeneratedScenario(spawns, enumerationIndex = index, positionDrawIndex = draw)
  }

  /**
   * Generates the scenarios in enumeration order.
   *
   * @param fromIndex First enumeration index to generate.
   * @param statistics Collects the position draws, if given.
   * @return The scenarios with an enumeration index of at least [fromIndex].
   */
  fun generateAll(
      fromIndex: Long = 0L,
      statistics: DrawStatistics? = null,
  ): Sequence<GeneratedScenario> = sequence {
    for (index in fromIndex until indexCount) scenarioAt(index, statistics)?.let { yield(it) }
  }

  /** Returns the derived counts, for logging. */
  fun countSummary(): String =
      "$patternCount cell patterns, $configurationCount configurations, $positionDraws position " +
          "draw(s) per configuration, $scenarioCount scenarios"

  /**
   * A configuration: ego lane and speed, and the speed of the vehicle in every occupied cell.
   *
   * @property index Configuration index.
   * @property egoLane Lane of the ego.
   * @property egoSpeedKmh Start speed of the ego.
   * @property speedByCell Start speed by cell index, for the occupied cells only.
   */
  private data class Configuration(
      val index: Long,
      val egoLane: Int,
      val egoSpeedKmh: Int,
      val speedByCell: Map<Int, Int>,
  )

  private fun configurationAt(index: Long): Configuration {
    var digits = index % patternCount
    val egoSlot = index / patternCount
    val egoLane = (egoSlot % GRID_SIZE).toInt()
    val egoCell = cellIndex(MIDDLE_ROW, egoLane)
    val speedByCell = LinkedHashMap<Int, Int>()
    for (cell in 0 until CELL_COUNT) {
      if (cell == egoCell) continue
      val digit = (digits % statesPerCell).toInt()
      digits /= statesPerCell
      if (digit > 0) speedByCell[cell] = otherSpeedsKmh[digit - 1]
    }
    return Configuration(
        index = index,
        egoLane = egoLane,
        egoSpeedKmh = egoSpeedsKmh[(egoSlot / GRID_SIZE).toInt()],
        speedByCell = speedByCell)
  }

  /** Positions by cell index for position draw [draw] of [configuration]. */
  private fun positionsOf(
      configuration: Configuration,
      draw: Int,
      statistics: DrawStatistics?,
  ): Map<Int, Float> {
    if (!variance) {
      return configuration.speedByCell.keys.associateWith { rowIntervals[it / GRID_SIZE].center() }
    }
    val earlierDraws = (0 until draw).map { positionsOf(configuration, it, statistics = null) }
    repeat(maxAttempts) { attempt ->
      val rng = Random(seedFor(configuration.index, draw, attempt))
      val positions = drawPositions(configuration, rng, statistics)
      if (positions !in earlierDraws) return positions
      statistics?.let { it.duplicateDraws++ }
    }
    error(
        "Position draw $draw of configuration ${configuration.index} equals an earlier draw in " +
            "all $maxAttempts attempts")
  }

  private fun drawPositions(
      configuration: Configuration,
      rng: Random,
      statistics: DrawStatistics?,
  ): Map<Int, Float> = buildMap {
    for (lane in 0 until GRID_SIZE) {
      val cells =
          (BOTTOM_ROW..TOP_ROW)
              .map { row -> cellIndex(row, lane) }
              .filter { it in configuration.speedByCell }
      if (cells.isNotEmpty()) putAll(drawLane(configuration, lane, cells, rng, statistics))
    }
  }

  private fun drawLane(
      configuration: Configuration,
      lane: Int,
      cells: List<Int>,
      rng: Random,
      statistics: DrawStatistics?,
  ): Map<Int, Float> {
    repeat(maxAttempts) {
      statistics?.let { it.laneDraws++ }
      val positions =
          cells.associateWith { cell ->
            roundToPositionStep(rowIntervals[cell / GRID_SIZE].pick(rng, variance = true))
          }
      if (startConditionsHold(configuration, lane, positions)) return positions
      statistics?.let { it.laneRejections++ }
    }
    error(
        "No positions on lane $lane of configuration ${configuration.index} satisfy " +
            "minStartGapMeters=$minStartGapMeters and minStartTtcSeconds=$minStartTtcSeconds in " +
            "$maxAttempts attempts")
  }

  private fun startConditionsHold(
      configuration: Configuration,
      lane: Int,
      positions: Map<Int, Float>,
  ): Boolean {
    val vehicles =
        positions.map { (cell, position) -> position to configuration.speedByCell.getValue(cell) } +
            if (lane == configuration.egoLane) {
              listOf(egoPositionMeters to configuration.egoSpeedKmh)
            } else {
              emptyList()
            }
    return vehicles
        .sortedBy { it.first }
        .zipWithNext()
        .all { (follower, leader) ->
          val gap = leader.first - follower.first - vehicleLengthMeters
          val closingSpeedMps = (follower.second - leader.second) / KMH_PER_MPS
          gap >= minStartGapMeters &&
              (closingSpeedMps <= 0.0 || gap / closingSpeedMps >= minStartTtcSeconds)
        }
  }

  private fun seedFor(configuration: Long, draw: Int, attempt: Int): Long =
      splitMix64(
          splitMix64(splitMix64(seed.toLong()) xor configuration) xor
              ((draw.toLong() shl Int.SIZE_BITS) or attempt.toLong()))

  /** Creates generators from command-line arguments. */
  companion object {
    private const val KMH_PER_MPS = 3.6
    private const val POSITION_STEPS_PER_METER = 10

    /**
     * Creates a generator from command-line arguments. Every argument is optional and defaults to
     * the constructor default: `--egoSpeeds=80,100,120`, `--otherSpeeds=70,90,110,130`,
     * `--types=70:CALM,90:NORMAL,110:SPORTY,130:SPEEDY`, `--positionDraws=1`, `--variance=true`,
     * `--minStartGap=10`, `--minStartTtc=4` and `--maxAttempts=1000`.
     *
     * @param args Command-line arguments.
     * @return The generator.
     */
    fun fromArgs(args: Array<String>): GridTrafficScenarioGenerator {
      val defaults = GridTrafficScenarioGenerator()
      return GridTrafficScenarioGenerator(
          egoSpeedsKmh = CliArgs.optionalIntList(args, "egoSpeeds") ?: defaults.egoSpeedsKmh,
          otherSpeedsKmh = CliArgs.optionalIntList(args, "otherSpeeds") ?: defaults.otherSpeedsKmh,
          typeBySpeedKmh =
              CliArgs.optionalString(args, "types")?.let(::parseTypes) ?: defaults.typeBySpeedKmh,
          positionDraws = CliArgs.optionalInt(args, "positionDraws") ?: defaults.positionDraws,
          variance = CliArgs.optionalBoolean(args, "variance", defaults.variance),
          minStartGapMeters =
              CliArgs.optionalDouble(args, "minStartGap") ?: defaults.minStartGapMeters,
          minStartTtcSeconds =
              CliArgs.optionalDouble(args, "minStartTtc") ?: defaults.minStartTtcSeconds,
          maxAttempts = CliArgs.optionalInt(args, "maxAttempts") ?: defaults.maxAttempts,
      )
    }

    /** Parses `70:CALM,90:NORMAL` into a map from speed to type. */
    private fun parseTypes(raw: String): Map<Int, GridVehicleType> =
        raw.split(",").associate { part ->
          val (speed, type) = part.split(":").map { it.trim() }
          speed.toInt() to GridVehicleType.valueOf(type)
        }

    private fun requireDistinctPositive(speeds: List<Int>, name: String) {
      require(speeds.isNotEmpty() && speeds.all { it > 0 } && speeds.toSet().size == speeds.size) {
        "$name must be distinct positive speeds but was $speeds"
      }
    }

    private fun roundToPositionStep(positionMeters: Float): Float =
        (positionMeters * POSITION_STEPS_PER_METER).roundToInt().toFloat() /
            POSITION_STEPS_PER_METER

    private fun power(base: Int, exponent: Int): Long {
      var result = 1L
      repeat(exponent) { result = Math.multiplyExact(result, base.toLong()) }
      return result
    }

    /** SplitMix64 finalizer, a fast bijective mix of the seed parts. */
    private fun splitMix64(value: Long): Long {
      var z = value + 0x9E3779B97F4A7C15uL.toLong()
      z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
      z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
      return z xor (z ushr 31)
    }
  }
}

/**
 * Counts the position draws of [GridTrafficScenarioGenerator].
 *
 * @property laneDraws Number of drawn lanes.
 * @property laneRejections Number of drawn lanes that violated the start conditions.
 * @property duplicateDraws Number of position draws that equalled an earlier draw of their
 *   configuration.
 */
class DrawStatistics(
    var laneDraws: Long = 0L,
    var laneRejections: Long = 0L,
    var duplicateDraws: Long = 0L,
) {
  override fun toString(): String =
      "$laneDraws lane draws, $laneRejections rejected by the start conditions " +
          "(${"%.1f".format(Locale.US, 100.0 * laneRejections / laneDraws.coerceAtLeast(1))}%), " +
          "$duplicateDraws duplicate position draws"
}
