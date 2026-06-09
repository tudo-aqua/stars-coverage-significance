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
import kotlin.math.sqrt
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.tscInstanceChangeData

/** Statistical analysis of the time until the TSC instance first changes per (mutant, scenario). */
object TSCInstanceChangeAnalysis {

  /** Prints descriptive statistics for the time-until-first-TSC-instance-change distribution. */
  fun evaluate() {
    println("Starting TSCInstanceChangeAnalysis.")

    val data = tscInstanceChangeData
    val times = data.mapNotNull { it.millisUntilFirstChange }.map { it.toDouble() }

    val withChange = times.size
    val withoutChange = data.size - withChange
    println(
        "Entries: ${data.size} total - $withChange with TSC instance change, $withoutChange without.")

    if (times.isEmpty()) {
      println("No TSC instance changes observed; statistics unavailable.")
      println("Finished TSCInstanceChangeAnalysis.")
      return
    }

    val sorted = times.sorted()
    val n = sorted.size
    val min = sorted.first()
    val max = sorted.last()
    val mean = times.average()
    val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 else sorted[n / 2]
    val stddev = sqrt(times.sumOf { (it - mean) * (it - mean) } / n)

    println("Time Until First TSC Instance Change (ms):")
    println("  Min:    $min")
    println("  Max:    $max")
    println("  Mean:   $mean")
    println("  Median: $median")
    println("  StdDev: $stddev")
    println("")

    val sortedData =
        data.filter { it.millisUntilFirstChange != null }.sortedBy { it.millisUntilFirstChange }
    sortedData.take(5).forEach { println(it) }
    sortedData.takeLast(5).forEach { println(it) }

    val csvPath =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "tsc_instance_change_analysis",
            "tsc_instance_change_times.csv")
    Files.createDirectories(csvPath.parent)
    csvPath.writeText(
        "millisUntilFirstChange\n" +
            times.joinToString(separator = "\n") { it.toLong().toString() })
    println("Wrote CSV to $csvPath")

    println("Finished TSCInstanceChangeAnalysis.")
  }
}
