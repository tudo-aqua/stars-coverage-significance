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

import kotlin.collections.forEach
import kotlin.collections.groupBy
import tools.aqua.stars.core.utils.getPlot
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.mutantFailuresFromDB
import tools.aqua.stars.coverage.significance.utils.plotDataAsBarChart

/** Statistical analysis of the long-tail distribution. */
object LongTailDistributionPostEvaluation {

  /** Group mutant failures by TSC ID. */
  val longTailByTSC = mutantFailuresFromDB.groupBy { it.tscId }

  /** Plots the long-tail distribution for each TSC. */
  fun evaluate() {
    longTailByTSC.forEach { (tscId, failures) ->
      val groupedFailuresByTSCInstance = failures.groupBy { it.currentTSCInstance }

      // Count number of failures per TSC instance and order counts descending to form a long-tail
      val countsPerInstance = groupedFailuresByTSCInstance.mapValues { (_, v) -> v.size }
      val orderedCounts = countsPerInstance.values.sortedDescending()

      if (orderedCounts.isEmpty()) return@forEach

      // Create plot using core getPlot utility. x-axis will be indices (0..n-1)
      val plot =
          getPlot(
              "LongTail",
              xValues = orderedCounts.mapIndexed { index, _ -> index },
              yValues = orderedCounts,
          )

      if (plot == null) return@forEach

      val subfolder = "longtail_by_tsc/${tscId}"
      val outPath = java.nio.file.Path.of(POST_EVALUATION_BASE_DIR, subfolder)

      plotDataAsBarChart(
          plot,
          fileName = "longtail_by_tsc_${tscId}.png",
          title = "Long-tail for TSC $tscId",
          path = outPath,
      )
    }
  }
}
