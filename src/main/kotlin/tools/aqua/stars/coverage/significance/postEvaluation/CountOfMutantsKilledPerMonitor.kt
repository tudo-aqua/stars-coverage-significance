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
import java.util.UUID
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.distinctMutantFailuresFiltered

object CountOfMutantsKilledPerMonitor {
  fun evaluate() {
    println("Starting CountOfMutantsKilledPerMonitor.")
    val monitorToFailedMutantsMap =
        Monitors.entries.associate { m -> m.name to mutableSetOf<UUID>() }

    distinctMutantFailuresFiltered.forEach { failure ->
      Monitors.entries.forEach { m ->
        if (failure.monitorBitmask and m.mask == m.mask)
            monitorToFailedMutantsMap[m.name]!!.add(failure.mutantID)
      }
    }

    val csvFileName = "countOfMutantsKilledPerMonitor.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "count_of_mutants_killed_per_monitor",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        monitorToFailedMutantsMap.keys.joinToString(separator = ",") { it } +
            "\n" +
            monitorToFailedMutantsMap.values.joinToString(separator = ",") { "${it.size}" })
    println("Finished CountOfMutantsKilledPerMonitor.")
  }
}
