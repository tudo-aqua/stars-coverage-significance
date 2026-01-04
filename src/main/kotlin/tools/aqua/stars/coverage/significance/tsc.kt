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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.tsc.builder.tsc
import tools.aqua.stars.data.sumo.dynamicData.TickDifferenceMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TickUnitMilliseconds
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle

@SuppressWarnings("StringLiteralDuplication")
/** TSC for SUMO highway scenarios. */
fun tsc() =
    tsc<Vehicle, TimeStep, TickUnitMilliseconds, TickDifferenceMilliseconds>("SUMO Highway TSC") {
      all("Root") {
        exclusive("Traffic Density") {
          leaf("Low Traffic Density") { condition { true } }
          leaf("Medium Traffic Density") { condition { true } }
          leaf("High Traffic Density") { condition { true } }
        }
        exclusive("Lane") {
          all("Left Lane") {
            condition { true }
            optional("Road") {
              leaf("Vehicle in Front") { condition { true } }
              leaf("Vehicle Behind") { condition { true } }
              leaf("Vehicle on Right Lane") { condition { true } }
            }
            exclusive("Maneuver") {
              leaf("Follow Lane") { condition { true } }
              leaf("Change to Right Lane") { condition { true } }
            }
            all("Middle Lane") {
              condition { true }
              optional("Road") {
                leaf("Vehicle in Front") { condition { true } }
                leaf("Vehicle Behind") { condition { true } }
                leaf("Vehicle on Left Lane") { condition { true } }
                leaf("Vehicle on Right Lane") { condition { true } }
              }
              exclusive("Maneuver") {
                leaf("Follow Lane") { condition { true } }
                leaf("Change to Left Lane") { condition { true } }
                leaf("Change to Right Lane") { condition { true } }
              }
            }
            all("Right Lane") {
              condition { true }
              optional("Road") {
                leaf("Vehicle in Front") { condition { true } }
                leaf("Vehicle Behind") { condition { true } }
                leaf("Vehicle on Left Lane") { condition { true } }
              }
              exclusive("Maneuver") {
                leaf("Follow Lane") { condition { true } }
                leaf("Change to Left Lane") { condition { true } }
              }
            }
          }
        }
      }
    }
