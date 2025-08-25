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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.builder.*
import tools.aqua.stars.data.av.dataclasses.*

private const val FULL_TSC = "full TSC"
private const val LAYER_1_2 = "layer 1+2"
private const val LAYER_4 = "layer 4"
private const val LAYER_1_2_4 = "layer 1+2+4"
private const val LAYER_4_5 = "layer (4)+5"
private const val LAYER_PEDESTRIAN = "pedestrian"
private const val LAYER_MULTI_LANE_DYNAMIC_RELATIONS = "multi-lane-dynamic-relations"

/**
 * Returns the [TSC] with the dataclasses [Actor], [TickData], [TickDataUnitSeconds], and
 * [TickDataDifferenceSeconds] that is used in this experiment.
 */
@Suppress("StringLiteralDuplication")
fun tsc() =
    tsc<Actor, TickData, TickDataUnitSeconds, TickDataDifferenceSeconds> {
      all("TSCRoot") {
        projections {
          projectionRecursive(FULL_TSC) // all
          projection(LAYER_1_2) // static
          projection(LAYER_4) // dynamic
          projection(LAYER_1_2_4) // static + dynamic
          projection(LAYER_4_5) // environment
          projection(LAYER_PEDESTRIAN) // pedestrian
          projection(LAYER_MULTI_LANE_DYNAMIC_RELATIONS)
        }

        exclusive("Weather") {
          projections {
            projectionRecursive(LAYER_4_5)
            projectionRecursive(LAYER_PEDESTRIAN)
          }

          leaf("Clear") { condition { _ -> true } }
          leaf("Cloudy") { condition { _ -> true } }
          leaf("Wet") { condition { _ -> true } }
          leaf("Wet Cloudy") { condition { _ -> true } }
          leaf("Soft Rain") { condition { _ -> true } }
          leaf("Mid Rain") { condition { _ -> true } }
          leaf("Hard Rain") { condition { _ -> true } }
        }

        exclusive("Road Type") {
          projections {
            projection(LAYER_1_2)
            projection(LAYER_4)
            projection(LAYER_1_2_4)
            projection(LAYER_PEDESTRIAN)
            projection(LAYER_MULTI_LANE_DYNAMIC_RELATIONS)
          }

          all("Junction") {
            condition { _ -> true }

            projections {
              projection(LAYER_PEDESTRIAN)
              projection(LAYER_1_2)
              projection(LAYER_4)
              projection(LAYER_1_2_4)
            }

            optional("Dynamic Relation") {
              projections {
                projection(LAYER_PEDESTRIAN)
                projectionRecursive(LAYER_4)
                projectionRecursive(LAYER_1_2_4)
              }

              leaf("Pedestrian Crossed") {
                projections { projection(LAYER_PEDESTRIAN) }

                condition { _ -> true }
              }

              leaf("Must Yield") {
                condition { _ -> true }

                monitors { monitor("Did not yield") { _ -> true } }
              }

              leaf("Following Leading Vehicle") {
                projections { projection(LAYER_4) }

                condition { _ -> true }
              }
            }

            exclusive("Maneuver") {
              projections {
                projectionRecursive(LAYER_1_2)
                projectionRecursive(LAYER_1_2_4)
              }

              leaf("Lane Follow") { condition { _ -> true } }
              leaf("Right Turn") { condition { _ -> true } }
              leaf("Left Turn") { condition { _ -> true } }
            }
          }
          all("Multi-Lane") {
            projections {
              projection(LAYER_PEDESTRIAN)
              projection(LAYER_1_2)
              projection(LAYER_4)
              projection(LAYER_1_2_4)
              projection(LAYER_MULTI_LANE_DYNAMIC_RELATIONS)
            }

            condition { tick -> isOnMultilaneRoad.holds(tick) }

            optional("Dynamic Relation") {
              projections {
                projection(LAYER_PEDESTRIAN)
                projectionRecursive(LAYER_4)
                projectionRecursive(LAYER_1_2_4)
                projectionRecursive(LAYER_MULTI_LANE_DYNAMIC_RELATIONS)
              }
              leaf("Oncoming traffic") { condition { _ -> true } }
              leaf("Overtaking") {
                condition { _ -> true }
                monitors { monitor("Right Overtaking") { _ -> true } }
              }
              leaf("Pedestrian Crossed") {
                projections { projection(LAYER_PEDESTRIAN) }

                condition { _ -> true }
              }
              leaf("Following Leading Vehicle") {
                projections { projection(LAYER_4) }

                condition { _ -> true }
              }
            }

            exclusive("Maneuver") {
              projections {
                projectionRecursive(LAYER_1_2)
                projectionRecursive(LAYER_1_2_4)
              }
              leaf("Lane Change") { condition { _ -> true } }
              leaf("Lane Follow") { condition { _ -> true } }
            }

            bounded("Stop Type", Pair(0, 1)) {
              projections {
                projectionRecursive(LAYER_1_2)
                projectionRecursive(LAYER_1_2_4)
              }

              leaf("Has Red Light") {
                condition { _ -> true }
                monitors { monitor("Crossed red light") { _ -> true } }
              }
            }
          }
          all("Single-Lane") {
            projections {
              projection(LAYER_PEDESTRIAN)
              projection(LAYER_1_2)
              projection(LAYER_4)
              projection(LAYER_1_2_4)
            }

            condition { _ -> true }

            optional("Dynamic Relation") {
              projections {
                projection(LAYER_PEDESTRIAN)
                projectionRecursive(LAYER_4)
                projectionRecursive(LAYER_1_2_4)
              }

              leaf("Oncoming traffic") { condition { _ -> true } }

              leaf("Pedestrian Crossed") {
                projections { projection(LAYER_PEDESTRIAN) }

                condition { _ -> true }
              }

              leaf("Following Leading Vehicle") {
                projections {
                  projection(LAYER_4)
                  projection(LAYER_1_2_4)
                }

                condition { _ -> true }
              }
            }

            bounded("Stop Type", Pair(0, 1)) {
              projections {
                projectionRecursive(LAYER_1_2)
                projectionRecursive(LAYER_1_2_4)
              }

              leaf("Has Stop Sign") {
                condition { _ -> true }
                monitors { monitor("Stopped at stop sign") { _ -> true } }
              }
              leaf("Has Yield Sign") { condition { _ -> true } }
              leaf("Has Red Light") {
                condition { _ -> true }
                monitors { monitor("Crossed red light") { _ -> true } }
              }
            }
          }
        }

        exclusive("Traffic Density") {
          projections {
            projectionRecursive(LAYER_4_5)
            projectionRecursive(LAYER_4)
            projectionRecursive(LAYER_1_2_4)
          }

          leaf("High Traffic") { condition { _ -> true } }
          leaf("Middle Traffic") { condition { _ -> true } }
          leaf("Low Traffic") { condition { _ -> true } }
        }

        exclusive("Time of Day") {
          projections {
            projectionRecursive(LAYER_4_5)
            projectionRecursive(LAYER_PEDESTRIAN)
          }

          leaf("Sunset") { condition { _ -> true } }

          leaf("Noon") { condition { _ -> true } }
        }
      }
    }
