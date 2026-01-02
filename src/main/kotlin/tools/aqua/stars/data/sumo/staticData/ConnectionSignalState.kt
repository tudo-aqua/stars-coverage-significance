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

package tools.aqua.stars.data.sumo.staticData

/**
 * Right-of-way / traffic light state of a SUMO `<connection>` (attribute `state`).
 *
 * @property xmlCode The single-character code used in SUMO XML files.
 */
enum class ConnectionSignalState(val xmlCode: Char) {
  /** Green major (traffic light). */
  TL_GREEN_MAJOR('G'),

  /** Green minor (traffic light). */
  TL_GREEN_MINOR('g'),

  /** Red (traffic light). */
  TL_RED('r'),

  /** Red-yellow (traffic light): red but indicates upcoming green. */
  TL_RED_YELLOW('u'),

  /** Yellow major (traffic light). */
  TL_YELLOW_MAJOR('Y'),

  /** Yellow minor (traffic light). */
  TL_YELLOW_MINOR('y'),

  /** Traffic light off, blinking yellow. */
  TL_OFF_BLINKING('o'),

  /** Traffic light off, no signal program. */
  TL_OFF_NO_SIGNAL('O'),

  /** Major priority link. */
  MAJOR('M'),

  /** Minor priority link. */
  MINOR('m'),

  /** Equal priority. */
  EQUAL('='),

  /** Stop-controlled link. */
  STOP('s'),

  /** All-way stop. */
  ALL_WAY_STOP('w'),

  /** Zipper merge. */
  ZIPPER('Z'),

  /** Dead end. */
  DEAD_END('-'),

  /** Any value not known to this enum (keeps parsing robust across SUMO versions). */
  UNKNOWN('?');

  /** Static methods for [ConnectionSignalState]. */
  companion object {
    /**
     * Parses SUMO `state` attribute into [ConnectionSignalState].
     *
     * @param rawState The raw XML attribute value (e.g. `"M"`).
     */
    fun fromXml(rawState: String): ConnectionSignalState {
      val c = rawState.trim().firstOrNull() ?: return UNKNOWN
      return entries.firstOrNull { it.xmlCode == c } ?: UNKNOWN
    }
  }
}
