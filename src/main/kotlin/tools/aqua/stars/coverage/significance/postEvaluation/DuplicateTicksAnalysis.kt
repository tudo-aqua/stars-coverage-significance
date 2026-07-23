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
 * exact, then 6 decimals down to 0) and duplicate counts are reported at each level. Only actual
 * duplicate groups (2+ rows sharing the same rounded values) are written to the JSON output —
 * singleton groups carry no information beyond what the per-level summary already reports
 * (`distinctTicks` etc.), and at hundreds of millions of rows, writing one JSON entry per row would
 * itself produce an unusably large file.
 *
 * ## Memory design
 * At ~470M rows this previously ran out of heap (even with the `-Xmx300g` this task is configured
 * with) grouping via a `HashMap<key, idList>`: at that scale, most rows are distinct (especially at
 * finer precision), so the map ends up with close to one entry per row — and each entry (a boxed
 * key array, a `HashMap.Node`, and an `ArrayList`) costs on the order of 150-250 bytes of JVM
 * object overhead, multiple times the ~80 bytes/row the raw column data itself needs.
 *
 * This version instead **sorts** row indices by their rounded column tuple (reading directly from
 * the shared [DuplicateTickColumns.columns] arrays during comparisons, so no per-row key object is
 * ever materialized) and finds duplicate runs via one linear scan of the sorted order. Peak extra
 * memory per precision level is one boxed `Array<Int>` of row indices (~24 bytes/row) plus the
 * sort's internal merge buffer — an order of magnitude less than the hash-map approach — and a
 * group's member-id list is only allocated once a run of 2+ equal rows is actually found.
 *
 * The trade-off is CPU: sorting 470M rows 8 times (once per precision level) does on the order of
 * tens of billions of comparisons in total, so a full run over the whole table is expected to take
 * a while. That is a much better failure mode than an `OutOfMemoryError`.
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
   * Sorts row indices `0 until n` by their compared columns rounded to [decimals] places, then
   * scans the sorted order once to find runs of 2+ rows sharing the same rounded tuple, writing
   * each such run as a group directly to [writer] (see [DuplicateTicksAnalysis] for why singleton
   * runs are not written, and why sorting is used instead of a hash map).
   *
   * The sorted index array and all rounded-value comparisons are local to this call and go out of
   * scope as soon as it returns, so memory never accumulates across precision levels.
   */
  private fun writeGroupsForPrecision(
      writer: BufferedWriter,
      data: DuplicateTickColumns,
      n: Int,
      decimals: Int?,
      label: String,
      isLastLevel: Boolean,
  ): PrecisionSummary {
    val indices = Array(n) { it }
    indices.sortWith(Comparator { a, b -> compareRounded(data, a, b, decimals) })

    writer.write("    \"${label.jsonEscape()}\": [\n")

    var distinctTicks = 0
    var duplicateGroups = 0
    var rowsInDuplicateGroups = 0
    var maxGroupSize = 0
    var wroteFirstGroup = false

    var runStart = 0
    var i = 1
    while (i <= n) {
      val sameAsPrev = i < n && compareRounded(data, indices[i - 1], indices[i], decimals) == 0
      if (!sameAsPrev) {
        val runLength = i - runStart
        distinctTicks++
        if (runLength > maxGroupSize) maxGroupSize = runLength

        if (runLength > 1) {
          duplicateGroups++
          rowsInDuplicateGroups += runLength

          if (wroteFirstGroup) writer.write(",\n")
          val rowIds = (runStart until i).map { data.ids[indices[it]] }
          writer.write(
              "      ${groupToJson(data.columnNames, data.columns, indices[runStart], decimals, rowIds)}")
          wroteFirstGroup = true
        }

        runStart = i
      }
      i++
    }
    if (wroteFirstGroup) writer.write("\n")
    writer.write("    ]")
    writer.write(if (isLastLevel) "\n" else ",\n")

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

  /**
   * Total-order comparison of rows [rowA] and [rowB] over every compared column, each rounded to
   * [decimals] places (`null` = unrounded). Used both to sort row indices and, via `== 0`, to
   * detect duplicate runs in the sorted order.
   */
  private fun compareRounded(
      data: DuplicateTickColumns,
      rowA: Int,
      rowB: Int,
      decimals: Int?
  ): Int {
    for (c in data.columns.indices) {
      val ra = roundedValue(data.columns[c][rowA], decimals)
      val rb = roundedValue(data.columns[c][rowB], decimals)
      // Float.compareTo (unlike the < / > operators) gives a total order where NaN compares
      // equal to itself and greater than every other value — exactly what's needed to group NaN
      // ("no vehicle in this cell") together as equal.
      val cmp = ra.compareTo(rb)
      if (cmp != 0) return cmp
    }
    return 0
  }

  private fun roundedValue(value: Float, decimals: Int?): Float =
      if (decimals == null || value.isNaN()) value else roundTo(value, decimals)

  private fun roundTo(value: Float, decimals: Int): Float {
    val factor = Math.pow(10.0, decimals.toDouble())
    return (Math.round(value.toDouble() * factor) / factor).toFloat()
  }

  private fun groupToJson(
      columnNames: List<String>,
      columns: Array<FloatArray>,
      representativeRow: Int,
      decimals: Int?,
      rowIds: List<Int>
  ): String {
    val valuesJson =
        columnNames.indices.joinToString(",") { i ->
          val v = roundedValue(columns[i][representativeRow], decimals)
          "\"${columnNames[i]}\":${if (v.isNaN()) "null" else v.toString()}"
        }
    val idsJson = rowIds.joinToString(",")
    return "{\"values\":{$valuesJson},\"rowIds\":[$idsJson],\"count\":${rowIds.size}}"
  }

  private fun List<String>.toJsonStringArray(): String =
      "[${joinToString(",") { "\"${it.jsonEscape()}\"" }}]"

  private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

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
