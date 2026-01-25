/*
 * Copyright 2023-2026 The STARS Coverage Significance Authors
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

@file:Suppress("StringLiteralDuplication")

package tools.aqua.stars.coverage.significance.metrics

import kotlin.collections.component1
import kotlin.collections.component2
import tools.aqua.stars.core.metrics.providers.Loggable
import tools.aqua.stars.core.metrics.providers.Plottable
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.core.metrics.providers.SerializableMetric
import tools.aqua.stars.core.metrics.providers.Stateful
import tools.aqua.stars.core.metrics.providers.TSCAndTSCInstanceMetricProvider
import tools.aqua.stars.core.serialization.tsc.SerializableTSCNode
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.instance.TSCInstance
import tools.aqua.stars.core.tsc.instance.TSCInstanceNode
import tools.aqua.stars.core.types.*
import tools.aqua.stars.coverage.significance.db.dataclasses.MetricStartingValidTSCInstancesEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCInstanceEntry
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.MetricStartingValidTSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.ScenarioStartingConfigurationRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.utils.getJsonString

/**
 * This class implements the [TSCAndTSCInstanceMetricProvider] and tracks the occurred valid
 * [TSCInstance] for each [TSC].
 *
 * This class implements the [PostEvaluationMetricProvider] which evaluates the combined results of
 * valid [TSCInstance]s for all [TSC]s.
 *
 * This class implements the [Stateful] interface. Its state contains the [Map] of [TSC]s to a
 * [List] of valid [TSCInstance]s.
 *
 * This class implements the [SerializableMetric] interface. It serializes all valid [TSCInstance]
 * for their respective [TSC].
 *
 * This class implements [Loggable] and logs the final [Map] of invalid [TSCInstance]s for [TSC]s.
 *
 * This class implements [Plottable] and plots the distribution and temporal change of valid
 * [TSCInstance]s.
 *
 * @param E [EntityType].
 * @param T [TickDataType].
 * @param U [TickUnit].
 * @param D [TickDifference].
 */
class StartingValidTSCInstancesPerTSCMetric<
    E : EntityType<E, T, U, D>,
    T : TickDataType<E, T, U, D>,
    U : TickUnit<U, D>,
    D : TickDifference<D>,
>() : TSCAndTSCInstanceMetricProvider<E, T, U, D>, PostEvaluationMetricProvider<E, T, U, D> {
  /**
   * Map a [TSC] to a map in which the occurrences of valid [TSCInstanceNode]s at the beginning of
   * the scenario are stored:
   * - Map<tsc,Map<source,TSCInstance>>.
   */
  private val startingValidInstancesMap:
      MutableMap<
          TSC<E, T, U, D>,
          MutableMap<TSCInstance<E, T, U, D>, MutableList<String>>,
      > =
      mutableMapOf()

  /** This metric does not depend on another metric. */
  override val dependsOn: Any? = null

  /**
   * Track the valid [TSCInstance]s for each [TSC] in the [startingValidInstancesMap]. If the
   * current [tscInstance] is invalid it is skipped.
   *
   * @param tsc The current [TSC] for which the validity should be checked.
   * @param tscInstance The current [TSCInstance] which is checked for validity.
   */
  override fun evaluate(tsc: TSC<E, T, U, D>, tscInstance: TSCInstance<E, T, U, D>) {
    val sourceIdentifier = tscInstance.sourceIdentifier.replace(".export.xml", "")

    // Get current count of unique and valid TSC instance for the current TSC
    val validInstances = startingValidInstancesMap.getOrPut(tsc) { mutableMapOf() }

    // Source was already evaluated
    if (validInstances.values.any { it.contains(sourceIdentifier) }) {
      return
    }

    // Get already observed instances for current TSC
    val validInstanceList = validInstances.getOrPut(tscInstance) { mutableListOf() }

    // Add current instance to list of observed instances
    validInstanceList.add(sourceIdentifier)
  }

  /**
   * Calculates the combined [Map]s that contain the occurrences and their percentages combined for
   * all TSCs.
   */
  override fun postEvaluate() {
    // Code for database insertion
    db {
      val entries = mutableListOf<MetricStartingValidTSCInstancesEntry>()
      startingValidInstancesMap.forEach { (tsc, map) ->
        val tscEntry = TSCsRepository.getByJson(SerializableTSCNode(tsc.rootNode).getJsonString())
        val tscEntryId = tscEntry?.id
        checkNotNull(tscEntryId) { "TSC entry not found in database" }

        map.forEach { (tscInstance, sourceIdentifiers) ->
          val tscInstanceJsonString = SerializableTSCNode(tscInstance.rootNode).getJsonString()
          val tscInstanceEntryId =
              TSCInstancesRepository.insertIfAbsentReturnId(
                  TSCInstanceEntry(tscId = tscEntryId, instanceJson = tscInstanceJsonString))

          sourceIdentifiers.forEach { sourceIdentifier ->
            val scenarioStartingConfigurationEntry =
                ScenarioStartingConfigurationRepository.getByScenarioByHumanReadableScenarioId(
                    sourceIdentifier)
            val scenarioStartingConfigurationEntryId = scenarioStartingConfigurationEntry?.id
            checkNotNull(scenarioStartingConfigurationEntryId) {
              "Scenario starting configuration entry not found in database"
            }

            val entry =
                MetricStartingValidTSCInstancesEntry(
                    tscId = tscEntryId,
                    tscInstanceId = tscInstanceEntryId,
                    scenarioConfigId = scenarioStartingConfigurationEntryId)
            entries.add(entry)
          }
        }
      }
      MetricStartingValidTSCInstancesRepository.batchInsert(entries)
    }
  }

  override fun printPostEvaluationResult() {}
}
