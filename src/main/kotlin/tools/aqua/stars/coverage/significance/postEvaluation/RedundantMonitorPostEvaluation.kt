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

import tools.aqua.stars.coverage.significance.distinctMutantFailuresFiltered

/** Checks for redundant monitors. */
object RedundantMonitorPostEvaluation {
  /** Evaluates for redundant monitors. */
  fun evaluate() {
    println("Starting ScenarioByScenarioCrossTable.")

    val failures =
        Monitors.entries.associateWith { monitor ->
          distinctMutantFailuresFiltered
              .filter { it.monitorBitmask and monitor.mask == monitor.mask }
              .map { it.currentTSCInstance to it.mutantID }
              .toSet()
        }

    Monitors.entries.forEach { monitor ->
      val redundancy =
          Monitors.entries.mapNotNull { other ->
            if (monitor != other && (failures[monitor]!!.subtract(failures[other]!!)).isEmpty())
                other
            else null
          }

      if (redundancy.isNotEmpty()) {
        println(
            "Monitor $monitor is redundant, as all mutants it kills are also killed by ${redundancy}.")
      }
    }

    println("Finished ScenarioByScenarioCrossTable.")
  }
}
