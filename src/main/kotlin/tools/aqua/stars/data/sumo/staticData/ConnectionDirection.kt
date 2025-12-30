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

package tools.aqua.stars.data.sumo.staticData

/**
 * Direction code of a SUMO `<connection>` (attribute `dir`).
 *
 * Backed by the single-character encoding used by SUMO.
 *
 * @property xmlCode The single-character code used in SUMO XML files.
 */
enum class ConnectionDirection(val xmlCode: Char) {
  /** Right turn. */
  RIGHT('r'),

  /** Slight right turn (“partial right”). */
  PART_RIGHT('R'),

  /** Straight. */
  STRAIGHT('s'),

  /** Slight left turn (“partial left”). */
  PART_LEFT('L'),

  /** Left turn. */
  LEFT('l'),

  /** U-turn. */
  TURN('t'),

  /** U-turn in left-hand traffic. */
  TURN_LEFT_HAND('T'),

  /** Any value not known to this enum (keeps parsing robust across SUMO versions). */
  UNKNOWN('?');

  /** Static methods for [ConnectionDirection]. */
  companion object {
    /**
     * Parses SUMO `dir` attribute into [ConnectionDirection].
     *
     * @param rawDir The raw XML attribute value (e.g. `"s"`).
     */
    fun fromXml(rawDir: String): ConnectionDirection {
      val c = rawDir.trim().firstOrNull() ?: return UNKNOWN
      return entries.firstOrNull { it.xmlCode == c } ?: UNKNOWN
    }
  }
}
