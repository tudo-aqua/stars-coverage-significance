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
import kotlin.random.Random
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.REPETITIONS
import tools.aqua.stars.coverage.significance.failedMonitorMapping
import tools.aqua.stars.coverage.significance.longtailDistribution
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioFailure

object BaselinePostEvaluation {

  val longtail by lazy {
    longtailDistribution
        .map { it.tscInstanceId to it.longTailValue }
        .sortedByDescending { it.second }
  }

  val sum by lazy { longtail.sumOf { it.second } }

  val rnd = Random(42L)

  fun evaluate() {
    val values =
        REPETITIONS.associateWith { repetitions ->
          evaluateMutantKillingRandom(longtail, sum, repetitions) to
              evaluateMutantKillingGrid(repetitions)
        }

    val csvFileName = "baseline_random.csv"
    val path: Path =
        Path.of(
            POST_EVALUATION_BASE_DIR,
            "baseline",
            csvFileName,
        )
    Files.createDirectories(path.parent)
    path.writeText(
        values.toList().joinToString(
            prefix = "Repetitions, Mutants killed random, Mutants killed grid\n",
            separator = "\n") { (repetitions, mutantsKilled) ->
              "$repetitions, ${mutantsKilled.first}, ${mutantsKilled.second}"
            })
  }

  private fun evaluateMutantKillingRandom(
      longtail: List<Pair<UUID, Long>>,
      sum: Long,
      repetitions: Int
  ): Int {
    val drawnScenarios =
        (0..repetitions)
            .map {
              // Draw one random scenario from the longtail distribution
              val randomValue = rnd.nextLong(sum)

              val iterator = longtail.iterator()
              var currentElement = iterator.next()
              var currentValue = currentElement.second

              while (currentValue < randomValue && iterator.hasNext()) {
                currentElement = iterator.next()
                currentValue += currentElement.second
              }

              return@map currentElement
            }
            .sortedByDescending { it.second }
            .map { it.first }

    // for each scenario draw one random instance
    val drawnScenarioFailures =
        drawnScenarios.map { scenario ->
          failedMonitorMapping.filter { it.scenarioId == scenario }.random(rnd)
        }

    return evaluateKilling(drawnScenarioFailures)
  }

  private fun evaluateMutantKillingGrid(repetitions: Int): Int {
    val drawnScenarios = (0..repetitions).map { failedMonitorMapping.random(rnd) }

    return evaluateKilling(drawnScenarios)
  }

  private fun evaluateKilling(drawnScenarioFailures: List<ScenarioFailure>): Int {
    val drawnScenarioInstances =
        drawnScenarioFailures.map { it.scenarioInstanceFailures.random(rnd) }

    val relevantMonitors =
        drawnScenarioInstances.flatMap { scenarioInstance ->
          scenarioInstance.mutants.filter { mutant -> mutant.violations.any() }
        }

    val mutantsKilled = relevantMonitors.map { it.mutantId }.toSet().count()

    return mutantsKilled
  }
}
