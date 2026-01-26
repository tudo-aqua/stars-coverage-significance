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

package tools.aqua.stars.coverage.significance.manualTesting

import org.eclipse.sumo.libsumo.Vehicle as SumoVehicle

object MutantApplierTest {

  fun applyToEgoVehicle(egoVehId: String, m: Mutant) {
    check(SumoVehicle.getParameter(egoVehId, "has.driverstate.device") != "")
    SumoVehicle.setParameter(
        egoVehId, "device.driverstate.initialAwareness", m.initialAwareness.toString())
    check(
        SumoVehicle.getParameter(egoVehId, "device.driverstate.initialAwareness") ==
            "%.2f".format(m.initialAwareness))

    SumoVehicle.setParameter(
        egoVehId,
        "device.driverstate.headwayErrorCoefficient",
        m.headwayErrorCoefficient.toString())
    check(
        SumoVehicle.getParameter(egoVehId, "device.driverstate.headwayErrorCoefficient") ==
            "%.2f".format(m.headwayErrorCoefficient))
  }
}
