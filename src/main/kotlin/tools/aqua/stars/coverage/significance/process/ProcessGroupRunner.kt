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

/** Utility object for running and managing groups of processes. */
object ProcessGroupRunner {

  /**
   * Awaits the completion of all processes in the given list and checks their exit codes.
   *
   * @param groupLabel Label for the group of processes (used in error messages).
   * @param processes List of named processes to await.
   * @throws IllegalStateException if any process in the group exited with a non-zero code.
   */
  fun awaitAll(groupLabel: String, processes: List<NamedProcess>) {
    var ok = true

    processes.forEachIndexed { idx, p ->
      val code = p.process.waitFor()
      if (code != 0) {
        ok = false
        System.err.println("$groupLabel '${p.name}' (#$idx) exited with code=$code")
      }
    }

    check(ok) { "At least one $groupLabel failed." }
  }
}
