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

/**
 * Shared lead-time bucketing so `ReplayDeterminismAnalysis` (live double-run check) and
 * `ResumeFailureAggregation` (re-aggregation of the existing `G0FullLeadTimeSweepAnalysis` detail
 * files) report comparable breakdowns.
 */
object LeadTimeBuckets {

  private val upperBoundsSeconds = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0)

  /** E.g. `0.3 -> "0.0-0.5"`, `7.0 -> "5.0-10.0"`, `12.0 -> "10.0+"`. */
  fun bucketLabel(seconds: Double): String {
    var lower = 0.0
    for (upper in upperBoundsSeconds) {
      if (seconds < upper) return "$lower-$upper"
      lower = upper
    }
    return "$lower+"
  }
}
