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

import java.util.UUID
import java.util.logging.Logger
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import tools.aqua.stars.core.metrics.providers.Loggable
import tools.aqua.stars.core.metrics.providers.Plottable
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.Stateful
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.core.utils.ApplicationConstantsHolder.CONSOLE_INDENT
import tools.aqua.stars.core.utils.ApplicationConstantsHolder.CONSOLE_SEPARATOR
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.core.utils.plotDataAsBarChart
import tools.aqua.stars.core.utils.saveAsCSVFile
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFirstTSCInstanceChangeEntry
import tools.aqua.stars.coverage.significance.db.repositories.MetricFirstTSCInstanceChangeRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.data.sumo.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle

/**
 * Metric evaluating the first change in a TSC instance over time.
 *
 * @property evaluationRunEntryId [UUID] of the evaluation run.
 * @property tscEntryId [UUID] of the TSC being evaluated.
 * @property dependsOn [Any]? object that this metric depends on.
 * @property loggerIdentifier identifier (name) for the logger.
 * @property logger [Logger] instance.
 */
class FirstTSCInstanceChangeMetric(
    val evaluationRunEntryId: UUID,
    val tscEntryId: UUID,
    override val dependsOn: Any? = null,
    override val loggerIdentifier: String = "first-tsc-instance-change",
    override val logger: Logger = Loggable.getLogger(loggerIdentifier),
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    Stateful,
    Loggable,
    Plottable,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  /**
   * Data class representing the first change in a TSC instance.
   *
   * @property changedFrom The TSC instance before the change.
   * @property changedTo The TSC instance after the change.
   * @property firstChangeAfterXUnits The time in units after which the first change occurred
   */
  data class FirstChangeData(
      val changedFrom:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      val changedTo:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>? =
          null,
      val firstChangeAfterXUnits: TickUnitMilliseconds? = null,
  )

  /**
   * Map storing the first change tick for each source identifier.
   * - Map<sourceIdentifier,FirstChangeData>.
   */
  val instanceChangeMap: MutableMap<String, FirstChangeData> = mutableMapOf()

  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val sourceIdentifier = tscInstance.sourceIdentifier.replace(".export.xml", "")
    val existingChange =
        instanceChangeMap.getOrPut(sourceIdentifier) { FirstChangeData(changedFrom = tscInstance) }
    // If there is already a change recorded, do nothing
    if (existingChange.firstChangeAfterXUnits != null) return

    // If the TSC instance has changed, record the change
    if (existingChange.changedFrom != tscInstance) {
      instanceChangeMap[sourceIdentifier] =
          FirstChangeData(
              changedFrom = existingChange.changedFrom,
              changedTo = tscInstance,
              firstChangeAfterXUnits = tick.currentTickUnit)
    }
  }

  /**
   * Returns the state of the metric as a map.
   * - Map<sourceIdentifier,Map<TSCInstance (changedFrom), TSCInstance (changedTo),
   *   firstChangeAfterXMilliseconds>>.
   */
  override fun getState(): MutableMap<String, FirstChangeData> = instanceChangeMap

  /** Prints descriptive statistics for the time it took for the first TSC instance change. */
  override fun printState() {
    println(
        "\n$CONSOLE_SEPARATOR\n$CONSOLE_INDENT TSC Instance Change in Milliseconds \n$CONSOLE_SEPARATOR")

    val times = recordedTimesMillis()
    val total = instanceChangeMap.size
    val observed = times.size
    val missing = total - observed

    logInfo("Observed first-change times: $observed/$total")
    if (missing > 0) {
      logInfo("Missing (no first change recorded): $missing")
    }

    if (times.isEmpty()) {
      logInfo("No first-change times recorded; descriptive statistics are not available.")
      logInfo()
      return
    }

    val mean = times.average()
    val stdDevSample = standardDeviationSample(times, mean)
    val sorted = times.sorted()
    val min = sorted.first()
    val max = sorted.last()
    val median = percentile(sorted, 50.0)
    val p25 = percentile(sorted, 25.0)
    val p75 = percentile(sorted, 75.0)
    val iqr = p75 - p25
    val p90 = percentile(sorted, 90.0)
    val p95 = percentile(sorted, 95.0)

    logInfo("Mean time to first change: $mean ms")
    logInfo("Standard deviation (sample): $stdDevSample ms")
    logInfo("Min / Median / Max: $min ms / $median ms / $max ms")
    logInfo("P25 / P75 (IQR): $p25 ms / $p75 ms (IQR=$iqr ms)")
    logInfo("P90 / P95: $p90 ms / $p95 ms\n")

    instanceChangeMap.forEach { (sourceIdentifier, firstChangeData) ->
      logInfo(
          "First Change After: ${firstChangeData.firstChangeAfterXUnits} for Source Identifier: $sourceIdentifier")
      logFine("$CONSOLE_INDENT Changed From Instance: ${firstChangeData.changedFrom.rootNode}")
      logFine(
          "$CONSOLE_INDENT Changed To Instance: ${firstChangeData.changedTo?.rootNode ?: "<no change recorded>"}")
    }
    logInfo()
  }

  /** All recorded first-change times in milliseconds for which a change was observed. */
  private fun recordedTimesMillis(): List<Double> =
      instanceChangeMap.values
          .mapNotNull { it.firstChangeAfterXUnits }
          .map { it.tickMillis.toDouble() }

  /** Sample standard deviation (dividing by n-1). Returns 0.0 for n <= 1. */
  private fun standardDeviationSample(times: List<Double>, mean: Double): Double {
    val n = times.size
    if (n <= 1) return 0.0
    val variance = times.sumOf { (it - mean) * (it - mean) } / (n - 1)
    return sqrt(variance)
  }

  /**
   * Percentile with linear interpolation (inclusive endpoints).
   *
   * @param sortedTimes ascending sorted list.
   * @param p percentile in [0, 100].
   */
  private fun percentile(sortedTimes: List<Double>, p: Double): Double {
    if (sortedTimes.isEmpty()) return 0.0
    if (p <= 0.0) return sortedTimes.first()
    if (p >= 100.0) return sortedTimes.last()
    if (sortedTimes.size == 1) return sortedTimes.first()

    val n = sortedTimes.size
    val rank = (p / 100.0) * (n - 1) // 0..(n-1)
    val lo = floor(rank).toInt()
    val hi = ceil(rank).toInt()
    if (lo == hi) return sortedTimes[lo]

    val w = rank - lo
    return sortedTimes[lo] * (1.0 - w) + sortedTimes[hi] * w
  }

  /**
   * Writes a bar plot showing the histogram of first TSC instance change times in 1000ms buckets.
   */
  override fun writePlots() {
    val barPlotName = "firstTSCInstanceChangeHistogram_1000ms"
    val bucketSizeMs = 1000L

    val times =
        instanceChangeMap.values.mapNotNull { it.firstChangeAfterXUnits }.map { it.tickMillis }

    // Bucket by lower bound: 0-999 -> 0, 1000-1999 -> 1000, ...
    val bucketToCount: Map<Long, Int> =
        times.groupingBy { (it / bucketSizeMs) * bucketSizeMs }.eachCount()

    // Keep buckets in ascending order for a readable plot (this is not sorting raw values)
    val orderedBuckets = bucketToCount.keys.sorted()
    val yValues = orderedBuckets.map { bucketToCount[it]!!.toDouble() }

    val plot =
        getPlot(
            legendEntry = "Count",
            yValues = yValues,
            xAxisName = "Time bucket (each = 1000ms, ascending)",
            yAxisName = "Number of TSC instances",
            legendHeader = "First TSC Instance Change Histogram")

    plotDataAsBarChart(
        plot = plot, fileName = barPlotName, folder = "firstTSCInstanceChangeHistogram_1000ms")
  }

  /**
   * Data class representing statistics for a bucket.
   *
   * @property bucketStartMs The start of the bucket in milliseconds.
   * @property bucketEndMsInclusive The end of the bucket in milliseconds (inclusive).
   * @property bucketCenterMs The center of the bucket in milliseconds.
   * @property count The count of occurrences in the bucket.
   * @property mean The mean value in the bucket.
   * @property stdDevSample The standard deviation of the values in the bucket (dividing by n-1).
   * @property p10 The 10th percentile value in the bucket.
   * @property p25 The 25th percentile value in the bucket.
   * @property median The median value in the bucket.
   * @property p75 The 75th percentile value in the bucket.
   * @property p90 The 90th percentile value in the bucket.
   * @property p95 The 95th percentile value in the bucket.
   * @property iqr The interquartile range (IQR) value in the bucket.
   * @property min The minimum value in the bucket.
   * @property max The maximum value in the bucket.
   */
  private data class BucketStats(
      val bucketStartMs: Long,
      val bucketEndMsInclusive: Long,
      val bucketCenterMs: Long,
      val count: Int,
      val mean: Double,
      val stdDevSample: Double,
      val p10: Double,
      val p25: Double,
      val median: Double,
      val p75: Double,
      val p90: Double,
      val p95: Double,
      val iqr: Double,
      val min: Double? = null,
      val max: Double? = null,
  )

  /**
   * Computes statistics for each bucket of first TSC instance change times.
   *
   * @param bucketSizeMs The size of each bucket in milliseconds.
   * @param includeMinMax Whether to include min and max values in the statistics.
   * @param normalizeToBucket Whether to normalize values to the bucket's local coordinate system.
   * @return A list of [BucketStats] for each bucket.
   */
  private fun computeBucketStats(
      bucketSizeMs: Long = 1000L,
      includeMinMax: Boolean = true,
      normalizeToBucket: Boolean = true,
  ): List<BucketStats> {
    val times: List<Long> =
        instanceChangeMap.values.mapNotNull { it.firstChangeAfterXUnits }.map { it.tickMillis }

    if (times.isEmpty()) return emptyList()

    // Group absolute times by bucket start.
    val bucketToAbsoluteValues: Map<Long, List<Long>> =
        times.groupBy { (it / bucketSizeMs) * bucketSizeMs }

    val maxBucketStart = bucketToAbsoluteValues.keys.maxOrNull() ?: 0L
    val allBucketStarts = (0L..maxBucketStart step bucketSizeMs).toList()

    return allBucketStarts.map { start ->
      val endInclusive = start + bucketSizeMs - 1
      val center = start + bucketSizeMs / 2
      val absoluteValues: List<Long> = bucketToAbsoluteValues[start].orEmpty()

      // Optionally normalize values to the bucket's local coordinate system.
      // For a bucket [start, start+bucketSizeMs), the transformed values are in [0, bucketSizeMs).
      val values: List<Double> =
          if (normalizeToBucket) {
            absoluteValues.map { (it - start).toDouble() }
          } else {
            absoluteValues.map { it.toDouble() }
          }

      if (values.isEmpty()) {
        BucketStats(
            bucketStartMs = start,
            bucketEndMsInclusive = endInclusive,
            bucketCenterMs = center,
            count = 0,
            mean = Double.NaN,
            stdDevSample = Double.NaN,
            p10 = Double.NaN,
            p25 = Double.NaN,
            median = Double.NaN,
            p75 = Double.NaN,
            p90 = Double.NaN,
            p95 = Double.NaN,
            iqr = Double.NaN,
            min = if (includeMinMax) Double.NaN else null,
            max = if (includeMinMax) Double.NaN else null,
        )
      } else {
        val sorted = values.sorted()
        val mean = sorted.average()
        val stdDev = standardDeviationSample(sorted, mean)
        val p10 = percentile(sorted, 10.0)
        val p25 = percentile(sorted, 25.0)
        val median = percentile(sorted, 50.0)
        val p75 = percentile(sorted, 75.0)
        val p90 = percentile(sorted, 90.0)
        val p95 = percentile(sorted, 95.0)
        val iqr = p75 - p25
        val min = if (includeMinMax) sorted.first() else null
        val max = if (includeMinMax) sorted.last() else null

        BucketStats(
            bucketStartMs = start,
            bucketEndMsInclusive = endInclusive,
            bucketCenterMs = center,
            count = sorted.size,
            mean = mean,
            stdDevSample = stdDev,
            p10 = p10,
            p25 = p25,
            median = median,
            p75 = p75,
            p90 = p90,
            p95 = p95,
            iqr = iqr,
            min = min,
            max = max,
        )
      }
    }
  }

  /**
   * Data class representing global statistics for first TSC instance change times.
   *
   * @property nObserved The number of observed first change times.
   * @property mean The mean of the first change times.
   * @property stdDevSample The sample standard deviation of the first change times.
   * @property min The minimum of the first change times.
   * @property p10 The 10th percentile of the first change times.
   * @property p25 The 25th percentile of the first change times.
   * @property median The median of the first change times.
   * @property p75 The 75th percentile of the first change times.
   * @property p90 The 90th percentile of the first change times.
   * @property p95 The 95th percentile of the first change times.
   * @property max The maximum of the first change times.
   * @property iqr The interquartile range (IQR) of the first change times.
   */
  private data class GlobalStats(
      val nObserved: Int,
      val mean: Double,
      val stdDevSample: Double,
      val min: Double,
      val p10: Double,
      val p25: Double,
      val median: Double,
      val p75: Double,
      val p90: Double,
      val p95: Double,
      val max: Double,
      val iqr: Double,
  )

  /**
   * Computes global statistics for first TSC instance change times.
   *
   * @return [GlobalStats] or null if no times are recorded.
   */
  private fun computeGlobalStats(): GlobalStats? {
    val times = recordedTimesMillis()
    if (times.isEmpty()) return null

    val sorted = times.sorted()
    val mean = times.average()
    val stdDev = standardDeviationSample(times, mean)

    val min = sorted.first()
    val max = sorted.last()
    val p10 = percentile(sorted, 10.0)
    val p25 = percentile(sorted, 25.0)
    val median = percentile(sorted, 50.0)
    val p75 = percentile(sorted, 75.0)
    val p90 = percentile(sorted, 90.0)
    val p95 = percentile(sorted, 95.0)
    val iqr = p75 - p25

    return GlobalStats(
        nObserved = times.size,
        mean = mean,
        stdDevSample = stdDev,
        min = min,
        p10 = p10,
        p25 = p25,
        median = median,
        p75 = p75,
        p90 = p90,
        p95 = p95,
        max = max,
        iqr = iqr,
    )
  }

  /**
   * Writes CSV files containing bucket statistics and global statistics for first TSC instance
   * change times.
   */
  override fun writePlotDataCSV() {
    val bucketSizeMs = 1000L

    // 1) Per-bucket stats for histogram + per-bucket boxplots
    // For per-bucket boxplots, we want the distribution *within* each bucket.
    // Therefore we normalize values by subtracting the bucket start so that all
    // descriptive values are in [0, bucketSizeMs).
    val bucketStats =
        computeBucketStats(
            bucketSizeMs = bucketSizeMs,
            includeMinMax = true,
            normalizeToBucket = true,
        )
    val bucketFileName = "firstTSCInstanceChange_bucketStats_${bucketSizeMs}ms"

    val bucketSb = StringBuilder()

    bucketSb.appendLine(
        "bucket_start_ms;bucket_end_ms;bucket_center_ms;count;mean;stddev_sample;min;p10;p25;median;p75;p90;p95;max;iqr")
    bucketStats.forEach { b ->
      bucketSb.appendLine(
          "${b.bucketStartMs};" +
              "${b.bucketEndMsInclusive};" +
              "${b.bucketCenterMs};" +
              "${b.count};" +
              "${b.mean};" +
              "${b.stdDevSample};" +
              "${b.min ?: ""};" +
              "${b.p10};" +
              "${b.p25};" +
              "${b.median};" +
              "${b.p75};" +
              "${b.p90};" +
              "${b.p95};" +
              "${b.max ?: ""};" +
              "${b.iqr}")
    }

    saveAsCSVFile(
        csvString = bucketSb.toString(),
        fileName = bucketFileName,
        folder = loggerIdentifier,
    )

    val histogramFileName = "firstTSCInstanceChange_histogram_${bucketSizeMs}ms"
    val histogramSb = StringBuilder()
    histogramSb.appendLine("bucket_start_ms;bucket_end_ms;bucket_center_ms;count")
    bucketStats.forEach { b ->
      histogramSb.appendLine(
          "${b.bucketStartMs};${b.bucketEndMsInclusive};${b.bucketCenterMs};${b.count}")
    }

    saveAsCSVFile(
        csvString = histogramSb.toString(),
        fileName = histogramFileName,
        folder = loggerIdentifier,
    )

    val global = computeGlobalStats()
    val globalFileName = "firstTSCInstanceChange_globalStats"

    val globalSb = StringBuilder()
    globalSb.appendLine("n_observed;mean;stddev_sample;min;p10;p25;median;p75;p90;p95;max;iqr")

    if (global != null) {
      globalSb.appendLine(
          "${global.nObserved};" +
              "${global.mean};" +
              "${global.stdDevSample};" +
              "${global.min};" +
              "${global.p10};" +
              "${global.p25};" +
              "${global.median};" +
              "${global.p75};" +
              "${global.p90};" +
              "${global.p95};" +
              "${global.max};" +
              "${global.iqr}")
    }

    saveAsCSVFile(
        csvString = globalSb.toString(),
        fileName = globalFileName,
        folder = loggerIdentifier,
    )
  }

  override fun postEvaluate() {
    val entries = mutableListOf<MetricFirstTSCInstanceChangeEntry>()
    instanceChangeMap.forEach { (sourceIdentifier, firstChange) ->
      val scenarioStartingConfigurationEntryId =
          ScenarioStartingConfigurationRepository.getByHash(sourceIdentifier)?.id

      checkNotNull(scenarioStartingConfigurationEntryId) {
        "Scenario starting configuration not found for $sourceIdentifier"
      }
      val changeEntry =
          MetricFirstTSCInstanceChangeEntry(
              runId = evaluationRunEntryId,
              tscId = tscEntryId,
              scenarioConfigId = scenarioStartingConfigurationEntryId,
              firstChangeMillis = firstChange.firstChangeAfterXUnits?.tickMillis)
      entries.add(changeEntry)
    }
    MetricFirstTSCInstanceChangeRepository.batchInsert(entries)
  }

  override fun printPostEvaluationResult() {}
}
