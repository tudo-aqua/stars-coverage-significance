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

package tools.aqua.stars.coverage.significance.process

import java.util.concurrent.TimeUnit

/**
 * Data class representing a named process.
 *
 * @property name Name of the process.
 * @property process The process instance.
 */
data class NamedProcess(val name: String, val process: Process) {
  /**
   * Kills the process and its entire process tree.
   *
   * @param gracefulTimeoutMs Time in milliseconds to wait for graceful termination before forcing
   *   termination. Default is 2000 ms.
   */
  fun killProcessTree(gracefulTimeoutMs: Long = 2_000) {
    // Best-effort: try graceful terminate first
    try {
      val handle = process.toHandle()

      // Terminate descendants first (important on Windows + when using shells)
      handle.descendants().forEach { child -> runCatching { child.destroy() } }
      runCatching { handle.destroy() }

      // Give it a moment to exit cleanly
      runCatching { process.waitFor(gracefulTimeoutMs, TimeUnit.MILLISECONDS) }

      // Force kill anything still alive
      handle.descendants().forEach { child ->
        if (child.isAlive) runCatching { child.destroyForcibly() }
      }
      if (handle.isAlive) runCatching { handle.destroyForcibly() }
    } catch (_: Throwable) {
      // Fall back: last resort, try direct destroy calls
      runCatching { process.destroy() }
      runCatching { process.destroyForcibly() }
    }
  }
}
