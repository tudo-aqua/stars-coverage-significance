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

import kotlin.collections.forEach
import kotlin.collections.groupBy
import tools.aqua.stars.core.metrics.providers.PostEvaluationMetricProvider
import tools.aqua.stars.coverage.significance.mutantFailuresFromDB
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dataclasses.dynamicData.Vehicle

object LongTailDistributionPostEvaluation :
    PostEvaluationMetricProvider<
        Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds> {
  override val dependsOn = null

  val longTailByTSC = mutantFailuresFromDB.groupBy { it.tscId }

  override fun postEvaluate() {
    longTailByTSC.forEach { (tscId, failures) ->
      val groupedFailuresByTSCInstance = failures.groupBy { it.currentTSCInstance }
    }
  }

  override fun printPostEvaluationResult() {}
}
