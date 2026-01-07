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
 * Small, dependency-free console progress bar with elapsed time + ETA.
 *
 * Usage: val pb = ConsoleProgress(total, label = "Running SUMO") pb.render(0, "starting") ...
 * pb.step("scenarioId") ... pb.finish("done")
 */
class ConsoleProgress(
    private val total: Int,
    private val label: String = "",
    private val barWidth: Int = 30,
    private val maxMsgLen: Int = 60,
) {
  private val startNanos: Long = System.nanoTime()
  private var done: Int = 0

  init {
    require(total > 0) { "total must be > 0" }
    require(barWidth > 0) { "barWidth must be > 0" }
  }

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

  fun step(message: String = "") {
    done++
    render(done, message)
  }

  fun finish(message: String = "") {
    done = total
    render(done, message)
  }

  /** Static helper to format seconds as H:MM:SS. */
  companion object {
    fun formatHms(seconds: Double): String {
      val s = seconds.toLong().coerceAtLeast(0L)
      val h = TimeUnit.SECONDS.toHours(s)
      val m = TimeUnit.SECONDS.toMinutes(s) % 60
      val sec = s % 60
      return "%d:%02d:%02d".format(h, m, sec)
    }
  }
}
