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

import kotlin.math.roundToInt
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantScenarioChunkJobsRepository

private fun currentPidString(): String {
  // Prefer Java 9+ ProcessHandle; fall back to RuntimeMXBean parsing for older runtimes.
  val pid = runCatching { ProcessHandle.current().pid().toString() }.getOrNull()
  if (pid != null) return pid

  return runCatching {
        java.lang.management.ManagementFactory.getRuntimeMXBean().name.substringBefore('@')
      }
      .getOrElse { "unknown" }
}

/**
 * Utility to monitor and print the progress of mutant scenario chunk jobs for the latest evaluation
 * run. It periodically queries the database for the progress and prints a progress bar,
 * percentages, and estimated time remaining. It can be run while the evaluation is still running to
 * observe the progress in real time. It can also be run after the evaluation has completed to see
 * the final progress and timing information. It does not modify the database and can be safely run
 * multiple times.
 */
fun main() {
  val pid = currentPidString()
  val mainThread = Thread.currentThread()
  println(
      "StartProgressMonitor starting (pid=$pid, thread=${mainThread.name}#${mainThread.threadId()}).")

  DbBootstrap.connect()
  val latestRunId =
      EvaluationRunsRepository.getLatest()?.id
          ?: error("No evaluation runs found in EvaluationRunsRepository.")
  val t = Thread {
    val pidT = pid
    val tcur = Thread.currentThread()
    println("Progress monitor thread started (pid=$pidT, thread=${tcur.name}#${tcur.threadId()}).")

    var last = ""

    fun formatDuration(secondsTotal: Long): String {
      val s = secondsTotal.coerceAtLeast(0)
      val h = s / 3600
      val m = (s % 3600) / 60
      val sec = s % 60
      return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, sec)
        m > 0 -> "%dm %02ds".format(m, sec)
        else -> "%ds".format(sec)
      }
    }

    while (!Thread.currentThread().isInterrupted) {
      val p = transaction { MutantScenarioChunkJobsRepository.getProgress(latestRunId) }

      val completed = p.done + p.failed
      val total = p.total
      val nowMs = System.currentTimeMillis()

      val pct =
          if (total == 0L) 1.0 else (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)

      val barWidth = 40
      val filled = (pct * barWidth).roundToInt().coerceIn(0, barWidth)
      val bar = "[" + "#".repeat(filled) + "-".repeat(barWidth - filled) + "]"

      val base =
          "\r$bar ${(pct * 100).toInt()}%  " +
              "done=${p.done} failed=${p.failed} running=${p.running} pending=${p.pending} total=$total"

      val line = run {
        val firstStartedAt = p.firstStartedAt
        if (firstStartedAt == null || total <= 0L) {
          // No estimates until at least one job has been started.
          base
        } else {
          // Elapsed time is measured from the first job start stored in the database, so it does
          // not reset when the monitor is restarted.
          val elapsedMs = (nowMs - firstStartedAt.toEpochMilli()).coerceAtLeast(0L)
          val elapsedSec = (elapsedMs / 1000.0).roundToInt().toLong()

          // Keep waiting until the first job has completed rather than printing unstable ETAs.
          if (completed == 0L) {
            base + "  elapsed=${formatDuration(elapsedSec)}  eta=estimating..."
          } else {
            // Average throughput since the first job was started.
            val rate = completed.toDouble() / (elapsedMs / 1000.0).coerceAtLeast(1.0)
            val remaining = (total - completed).coerceAtLeast(0L)
            val remainingSec = (remaining / rate).roundToInt().toLong()
            val totalSec = elapsedSec + remainingSec

            base +
                "  elapsed=${formatDuration(elapsedSec)}" +
                "  remaining=${formatDuration(remainingSec)}" +
                "  total=${formatDuration(totalSec)}"
          }
        }
      }

      if (line != last) {
        print(line)
        last = line
      }

      Thread.sleep(10_000)
    }
  }

  t.name = "progress-monitor"
  t.isDaemon = false
  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            // Ensure we leave the console in a clean state.
            println()
            t.interrupt()
          })

  t.start()
  // Block main so the JVM does not exit immediately.
  t.join()
}
