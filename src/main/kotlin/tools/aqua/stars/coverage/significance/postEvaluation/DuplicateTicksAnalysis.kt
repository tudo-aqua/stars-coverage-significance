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

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.DuplicateTickColumns

/**
 * Checks how many ticks in `metric_failed_monitors` are duplicates of each other under decreasing
 * rounding precision.
 *
 * Two ticks count as "the same" when the ego vehicle's spatial relation to its neighbours (relative
 * bumper-to-bumper distances), the ego and neighbour speeds, and the ego and neighbour
 * accelerations all match — see [MetricFailedMonitorsTable.buildDuplicateTickCompareColumns] for
 * the exact column list. Absolute lane positions and monitor/target columns are excluded.
 *
 * Since these values are stored as floats, exact equality rarely holds for semantically identical
 * scenes, so the compared columns are rounded to a decreasing number of decimal places (starting
 * exact, then 6 decimals down to 0) and duplicate counts are reported at each level.
 *
 * This replaces an earlier Python script that kept getting killed (no error, just terminated) on
 * the server — almost certainly the OS OOM killer, since that script converted every group at every
 * precision level into a Python dict before serializing, multiplying per-row memory overhead by the
 * number of precision levels. This version processes one precision level at a time: it builds the
 * grouping map for that level, streams its groups straight to the output file, and discards the map
 * before moving to the next level, so peak memory never grows with the number of precision levels.
 */
object DuplicateTicksAnalysis {

  private val BASE_PATH = Path.of(POST_EVALUATION_BASE_DIR, "duplicate_ticks")

  /** `null` = exact (unrounded) values; then one fewer decimal place per step. */
  private val PRECISION_LEVELS: List<Int?> = listOf(null, 6, 5, 4, 3, 2, 1, 0)

  fun evaluate() {
    println("Starting DuplicateTicksAnalysis.")

    println("Loading comparison columns from metric_failed_monitors ...")
    val data = db { MetricFailedMonitorsTable.buildDuplicateTickCompareColumns() }
    val n = data.ids.size
    println("Loaded $n ticks, comparing on ${data.columnNames.size} columns.")

    Files.createDirectories(BASE_PATH)
    val jsonPath = BASE_PATH.resolve("duplicate_tick_groups.json")
    val summaries = mutableListOf<PrecisionSummary>()

    jsonPath.bufferedWriter().use { writer ->
      writer.write("{\n")
      writer.write("  \"totalTicks\": $n,\n")
      writer.write("  \"columns\": ${data.columnNames.toJsonStringArray()},\n")
      writer.write("  \"groupsByPrecision\": {\n")

      PRECISION_LEVELS.forEachIndexed { levelIndex, decimals ->
        val label = decimals?.let { "$it decimals" } ?: "exact"
        val isLastLevel = levelIndex == PRECISION_LEVELS.lastIndex
        println("Grouping at precision: $label ...")

        val summary = writeGroupsForPrecision(writer, data, n, decimals, label, isLastLevel)
        summaries += summary
        println(
            "  distinct=${summary.distinctTicks}  duplicateRows=${summary.duplicateRows} " +
                "(${summary.duplicatePct}%)  duplicateGroups=${summary.duplicateGroups} " +
                "maxGroupSize=${summary.maxGroupSize}")
      }

      writer.write("  },\n")
      writer.write("  \"summary\": [\n")
      summaries.forEachIndexed { i, s ->
        writer.write("    ${s.toJson()}")
        writer.write(if (i < summaries.lastIndex) ",\n" else "\n")
      }
      writer.write("  ]\n")
      writer.write("}\n")
    }

    println("Finished DuplicateTicksAnalysis. JSON written to: $jsonPath")
  }

  /**
   * Groups all rows in [data] by their compared columns rounded to [decimals] places, writes the
   * `"<label>": [ ... ]` entry (each group with its member row IDs and rounded values) directly to
   * [writer], and returns the summary statistics for this precision level.
   *
   * The grouping map is local to this call and goes out of scope as soon as it returns, so memory
   * never accumulates across precision levels.
   */
  private fun writeGroupsForPrecision(
      writer: BufferedWriter,
      data: DuplicateTickColumns,
      n: Int,
      decimals: Int?,
      label: String,
      isLastLevel: Boolean,
  ): PrecisionSummary {
    val groups = LinkedHashMap<RoundedKey, MutableList<Int>>()
    val probe = FloatArray(data.columns.size)

    for (row in 0 until n) {
      for (c in data.columns.indices) {
        val v = data.columns[c][row]
        probe[c] = if (decimals == null || v.isNaN()) v else roundTo(v, decimals)
      }
      val existing = groups[RoundedKey(probe)]
      if (existing != null) {
        existing.add(data.ids[row])
      } else {
        groups[RoundedKey(probe.copyOf())] = mutableListOf(data.ids[row])
      }
    }

    writer.write("    \"${label.jsonEscape()}\": [\n")
    val total = groups.size
    var idx = 0
    var duplicateGroups = 0
    var rowsInDuplicateGroups = 0
    var maxGroupSize = 0
    for ((key, rowIds) in groups) {
      writer.write("      ${groupToJson(data.columnNames, key.values, rowIds)}")
      idx++
      writer.write(if (idx < total) ",\n" else "\n")

      val size = rowIds.size
      if (size > maxGroupSize) maxGroupSize = size
      if (size > 1) {
        duplicateGroups++
        rowsInDuplicateGroups += size
      }
    }
    writer.write("    ]")
    writer.write(if (isLastLevel) "\n" else ",\n")

    val distinctTicks = groups.size
    val duplicateRows = n - distinctTicks
    val duplicatePctRaw = if (n > 0) 100.0 * duplicateRows / n else 0.0

    return PrecisionSummary(
        precision = label,
        distinctTicks = distinctTicks,
        duplicateRows = duplicateRows,
        duplicatePct = Math.round(duplicatePctRaw * 1000.0) / 1000.0,
        rowsInDuplicateGroups = rowsInDuplicateGroups,
        duplicateGroups = duplicateGroups,
        maxGroupSize = maxGroupSize)
  }

  private fun roundTo(value: Float, decimals: Int): Float {
    val factor = Math.pow(10.0, decimals.toDouble())
    return (Math.round(value.toDouble() * factor) / factor).toFloat()
  }

  private fun groupToJson(
      columnNames: List<String>,
      values: FloatArray,
      rowIds: List<Int>
  ): String {
    val valuesJson =
        columnNames.indices.joinToString(",") { i ->
          val v = values[i]
          "\"${columnNames[i]}\":${if (v.isNaN()) "null" else v.toString()}"
        }
    val idsJson = rowIds.joinToString(",")
    return "{\"values\":{$valuesJson},\"rowIds\":[$idsJson],\"count\":${rowIds.size}}"
  }

  private fun List<String>.toJsonStringArray(): String =
      "[${joinToString(",") { "\"${it.jsonEscape()}\"" }}]"

  private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

  /** Content-based equality/hash key over a (rounded) row's compared values. */
  private class RoundedKey(val values: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is RoundedKey && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()
  }

  private data class PrecisionSummary(
      val precision: String,
      val distinctTicks: Int,
      val duplicateRows: Int,
      val duplicatePct: Double,
      val rowsInDuplicateGroups: Int,
      val duplicateGroups: Int,
      val maxGroupSize: Int,
  ) {
    fun toJson(): String =
        "{\"precision\":\"${precision.jsonEscape()}\",\"distinctTicks\":$distinctTicks," +
            "\"duplicateRows\":$duplicateRows,\"duplicatePct\":$duplicatePct," +
            "\"rowsInDuplicateGroups\":$rowsInDuplicateGroups," +
            "\"duplicateGroups\":$duplicateGroups,\"maxGroupSize\":$maxGroupSize}"
  }
}
