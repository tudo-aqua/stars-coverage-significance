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

package tools.aqua.stars.coverage.significance

import java.util.concurrent.TimeUnit

/**
 * Simple console progress bar for tracking progress of long-running tasks.
 *
 * @param total Total number of steps.
 * @param label Optional label to prepend to the progress bar.
 * @param barWidth Width of the progress bar in characters.
 */
class ConsoleProgress(
    private val total: Int,
    private val label: String = "",
    private val barWidth: Int = 30,
) {
  private val startNanos: Long = System.nanoTime()
  private var done: Int = 0

  init {
    require(total > 0) { "total must be > 0" }
    require(barWidth > 0) { "barWidth must be > 0" }
  }

  /**
   * Renders the progress bar.
   *
   * @param doneOverride Override the current progress.
   * @param message Optional message to append to the progress bar.
   */
  fun render(doneOverride: Int = done, message: String = "") {
    val d = doneOverride.coerceIn(0, total)
    val ratio = d.toDouble() / total.toDouble()
    val filled = (ratio * barWidth).toInt().coerceIn(0, barWidth)
    val bar = "#".repeat(filled) + "-".repeat(barWidth - filled)
    val pct = ratio * 100.0

    val elapsedSec = (System.nanoTime() - startNanos) / 1e9
    val avgSec = if (d > 0) elapsedSec / d else 0.0
    val etaSec = avgSec * (total - d)

    val msg = message

    val prefix = if (label.isBlank()) "" else "$label "
    val line =
        "\r[$bar] ${"%6.2f".format(pct)}%  $d/$total  " +
            "elapsed=${formatHms(elapsedSec)}  eta=${formatHms(etaSec)}  " +
            prefix +
            msg

    print(line)
    if (d == total) println()
  }

  /**
   * Advances the progress bar by one step.
   *
   * @param message Optional message to append to the progress bar.
   */
  fun step(message: String = "") {
    done++
    render(done, message)
  }

  /**
   * Finishes the progress bar and renders the final message.
   *
   * @param message Optional message to append to the progress bar.
   */
  fun finish(message: String = "") {
    done = total
    render(done, message)
  }

  /** Static helper to format seconds as H:MM:SS. */
  companion object {
    /**
     * Formats seconds as H:MM:SS.
     *
     * @param seconds Seconds to format.
     */
    fun formatHms(seconds: Double): String {
      val s = seconds.toLong().coerceAtLeast(0L)
      val h = TimeUnit.SECONDS.toHours(s)
      val m = TimeUnit.SECONDS.toMinutes(s) % 60
      val sec = s % 60
      return "%d:%02d:%02d".format(h, m, sec)
    }
  }
}
