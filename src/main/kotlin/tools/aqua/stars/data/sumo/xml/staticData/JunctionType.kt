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

package tools.aqua.stars.data.sumo.xml.staticData

/**
 * SUMO junction type as used in `.net.xml` for `<junction type="...">`.
 *
 * The values are based on SUMO documentation (node types) and commonly observed `.net.xml` output:
 * - Documented node types include: priority, traffic_light, right_before_left, left_before_right,
 *   unregulated, priority_stop, traffic_light_unregulated, allway_stop, rail_signal, zipper,
 *   traffic_light_right_on_red, rail_crossing. :contentReference[oaicite:2]{index=2}
 * - `.net.xml` also contains `dead_end` and `internal` junctions.
 *   :contentReference[oaicite:3]{index=3}
 */
enum class JunctionType {
  /** Priority junction (right-of-way is determined by edge priorities). */
  PRIORITY,

  /** Junction controlled by a traffic light program. */
  TRAFFIC_LIGHT,

  /** Right-before-left priority rule. */
  RIGHT_BEFORE_LEFT,

  /** Left-before-right priority rule. */
  LEFT_BEFORE_RIGHT,

  /** Junction without explicit right-of-way regulation. */
  UNREGULATED,

  /** Priority junction with explicit stop behavior. */
  PRIORITY_STOP,

  /** Traffic light junction with unregulated behavior in parts. */
  TRAFFIC_LIGHT_UNREGULATED,

  /** All-way stop rule. */
  ALLWAY_STOP,

  /** Railway signal junction. */
  RAIL_SIGNAL,

  /** Zipper merge junction. */
  ZIPPER,

  /** Traffic light junction allowing right turn on red (depending on rules). */
  TRAFFIC_LIGHT_RIGHT_ON_RED,

  /** Railway crossing junction. */
  RAIL_CROSSING,

  /** Dead end junction. */
  DEAD_END,

  /** Internal junction used by SUMO to model intersection internals. */
  INTERNAL,

  /** Fallback for unknown / future SUMO values. */
  UNKNOWN;

  /** Static parser methods. */
  companion object {
    /**
     * Parses the junction type attribute from SUMO XML.
     *
     * @param raw Raw `type` attribute (may be blank).
     * @return Parsed [JunctionType] or [UNKNOWN] if not recognized.
     */
    fun fromXml(raw: String): JunctionType {
      val v = raw.trim()
      if (v.isEmpty()) return UNKNOWN

      return when (v.lowercase()) {
        "priority" -> PRIORITY
        "traffic_light" -> TRAFFIC_LIGHT
        "right_before_left" -> RIGHT_BEFORE_LEFT
        "left_before_right" -> LEFT_BEFORE_RIGHT
        "unregulated" -> UNREGULATED
        "priority_stop" -> PRIORITY_STOP
        "traffic_light_unregulated" -> TRAFFIC_LIGHT_UNREGULATED
        "allway_stop" -> ALLWAY_STOP
        "allwaystop" -> ALLWAY_STOP
        "rail_signal" -> RAIL_SIGNAL
        "zipper" -> ZIPPER
        "traffic_light_right_on_red" -> TRAFFIC_LIGHT_RIGHT_ON_RED
        "traffic_light_rightonred" -> TRAFFIC_LIGHT_RIGHT_ON_RED
        "rail_crossing" -> RAIL_CROSSING
        "dead_end" -> DEAD_END
        "internal" -> INTERNAL
        else -> UNKNOWN
      }
    }
  }
}
