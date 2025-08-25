/*
 * Copyright 2025 The STARS Coverage Significance Authors
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

package tools.aqua.stars.coverage

import tools.aqua.stars.core.validation.ManualLabelFile
import tools.aqua.stars.core.validation.ManualLabelTests
import tools.aqua.stars.core.validation.manuallyLabelledFile
import tools.aqua.stars.coverage.significance.getSimulationRuns
import tools.aqua.stars.coverage.significance.hasRedTrafficLight
import tools.aqua.stars.coverage.significance.isOnRoad18
import tools.aqua.stars.coverage.significance.isOnRoad19
import tools.aqua.stars.data.av.dataclasses.Actor
import tools.aqua.stars.data.av.dataclasses.TickData
import tools.aqua.stars.data.av.dataclasses.TickDataDifferenceSeconds
import tools.aqua.stars.data.av.dataclasses.TickDataUnitSeconds
import tools.aqua.stars.importer.carla.loadTicks

class ManualLabelingTests :
    ManualLabelTests<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>() {
  override val manualLabelTestFiles:
      List<ManualLabelFile<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds>> =
      listOf(manualTests)
}

val simulationRuns = getSimulationRuns("manual_tests")
val ticks = loadTicks(simulationRuns)

val manualTests =
    manuallyLabelledFile(ticks) {
      predicateHolds(isOnRoad19) { interval(TickDataUnitSeconds(0.0), TickDataUnitSeconds(1.45)) }
      predicateDoesNotHold(isOnRoad19) {
        interval(TickDataUnitSeconds(1.5), TickDataUnitSeconds(2.0))
      }
      predicateHolds(isOnRoad18) { interval(TickDataUnitSeconds(1.5), TickDataUnitSeconds(2.0)) }
      predicateDoesNotHold(isOnRoad18) {
        interval(TickDataUnitSeconds(0.0), TickDataUnitSeconds(1.45))
      }
      predicateHolds(hasRedTrafficLight) {
        interval(TickDataUnitSeconds(1.5), TickDataUnitSeconds(2.0))
      }
      predicateDoesNotHold(hasRedTrafficLight) {
        interval(TickDataUnitSeconds(0.0), TickDataUnitSeconds(1.45))
      }
    }
