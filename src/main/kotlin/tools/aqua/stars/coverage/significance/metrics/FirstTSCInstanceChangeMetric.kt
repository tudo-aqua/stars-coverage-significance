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

package tools.aqua.stars.coverage.significance.metrics

import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceAndTickMetricProvider
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricFirstTSCInstanceChangeEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricFirstTSCInstanceChangeRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

/**
 * Metric evaluating the first change in a TSC instance over time.
 *
 * @property evaluationRunEntryId [UUID] of the evaluation run.
 * @property tscEntryId [UUID] of the TSC being evaluated.
 * @property dependsOn [Any]? object that this metric depends on.
 */
class FirstTSCInstanceChangeMetric(
    val evaluationRunEntryId: UUID,
    val tscEntryId: UUID,
    override val dependsOn: Any? = null,
) :
    TSCAndTSCInstanceAndTickMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {

  /**
   * Data class representing the first change in a TSC instance.
   *
   * @property changedFrom The TSC instance before the change.
   * @property changedTo The TSC instance after the change.
   * @property firstChangeAfterXUnits The time in units after which the first change occurred
   */
  data class FirstChangeData(
      val changedFrom:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      val changedTo:
          TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>? =
          null,
      val firstChangeAfterXUnits: TickUnitMilliseconds? = null,
  )

  /**
   * Map storing the first change tick for each source identifier.
   * - Map<sourceIdentifier,FirstChangeData>.
   */
  val instanceChangeMap: MutableMap<String, FirstChangeData> = mutableMapOf()

  /**
   * Evaluates the metric for the given TSC, TSC instance, and tick.
   *
   * @param tsc The TSC being evaluated.
   * @param tscInstance The current TSC instance.
   * @param tick The current time step.
   */
  override fun evaluate(
      tsc: TSC<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tscInstance: TSCInstance<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>,
      tick: TimeStep
  ) {
    val sourceIdentifier = tscInstance.sourceIdentifier.replace(".export.xml", "")
    val existingChange =
        instanceChangeMap.getOrPut(sourceIdentifier) { FirstChangeData(changedFrom = tscInstance) }
    // If there is already a change recorded, do nothing
    if (existingChange.firstChangeAfterXUnits != null) return

    // If the TSC instance has changed, record the change
    if (existingChange.changedFrom != tscInstance) {
      instanceChangeMap[sourceIdentifier] =
          FirstChangeData(
              changedFrom = existingChange.changedFrom,
              changedTo = tscInstance,
              firstChangeAfterXUnits = tick.currentTickUnit)
    }
  }

  /** Persists the first TSC instance change data into the database. */
  override fun postEvaluate() {
    db {
      val entries = mutableListOf<MetricFirstTSCInstanceChangeEntry>()
      instanceChangeMap.forEach { (sourceIdentifier, firstChange) ->
        val scenarioStartingConfigurationEntryId =
            ScenarioStartingConfigurationRepository.getByScenarioByHumanReadableScenarioId(
                    sourceIdentifier)
                ?.id

        checkNotNull(scenarioStartingConfigurationEntryId) {
          "Scenario starting configuration not found for $sourceIdentifier"
        }
        val changeEntry =
            MetricFirstTSCInstanceChangeEntry(
                runId = evaluationRunEntryId,
                tscId = tscEntryId,
                scenarioConfigId = scenarioStartingConfigurationEntryId,
                firstChangeMillis = firstChange.firstChangeAfterXUnits?.tickMillis)
        entries.add(changeEntry)
      }
      MetricFirstTSCInstanceChangeRepository.batchInsert(entries)
    }
  }

  override fun printPostEvaluationResult() {}
}
