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

import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

object HighwayTrafficAnalysis {
  fun evaluate(longtailDistribution: List<Pair<ScenarioIdAndJSON, Int>>) {
    println("Starting HighwayTrafficAnalysis.")

    val csvPath: Path =
      Path.of(
        POST_EVALUATION_BASE_DIR,
        "highway_traffic_analysis",
        "highwayTrafficAnalysisValues.csv",
      )
    Files.createDirectories(csvPath.parent)

    csvPath.writeText(
      longtailDistribution.joinToString(
        prefix = "Scenario, Frequency in longtail\n",
        separator = "\n") {
        "${it.first.scenarioId},${it.second}"
      })
    println("Finished HighwayTrafficAnalysis.")
  }
}
