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

import kotlin.random.Random

/**
 * Represents a closed interval [start, end].
 *
 * @property start Start of the interval.
 * @property end End of the interval.
 */
data class Interval(val start: Float, val end: Float) {
  init {
    require(end >= start) { "Interval end must be >= start" }
  }

  /**
   * Returns the center point of the interval.
   *
   * @return Center point of the interval.
   */
  fun center(): Float = (start + end) / 2.0f

  /**
   * Picks a value from the interval.
   *
   * @param rng Random number generator to use.
   * @param variance If false, returns the center point; if true, returns a random point within the
   *   interval.
   * @return Picked value from the interval.
   */
  fun pick(rng: Random, variance: Boolean): Float =
      if (!variance) center() else start + rng.nextFloat() * (end - start)

  /**
   * Checks if the interval is non-empty (i.e., start <= end).
   *
   * @return True if the interval is non-empty, false otherwise.
   */
  fun isNonEmpty(): Boolean = end + 1e-12 >= start
}
