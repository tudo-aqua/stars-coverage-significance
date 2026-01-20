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

package tools.aqua.stars.data.sumo.xml.dynamicData

import tools.aqua.stars.core.types.TickUnit

/**
 * Implementation of the [TickUnit] interface for 'milliseconds' units.
 *
 * @property tickMillis Current tick value in milliseconds.
 */
data class TickUnitMilliseconds(val tickMillis: Long) :
    TickUnit<TickUnitMilliseconds, TickDifferenceMilliseconds>() {
  override fun plus(other: TickDifferenceMilliseconds): TickUnitMilliseconds =
      TickUnitMilliseconds(this.tickMillis + other.differenceMillis)

  override fun minus(other: TickUnitMilliseconds): TickDifferenceMilliseconds =
      TickDifferenceMilliseconds(this.tickMillis - other.tickMillis)

  override fun minus(other: TickDifferenceMilliseconds): TickUnitMilliseconds =
      TickUnitMilliseconds(this.tickMillis - other.differenceMillis)

  override fun compareTo(other: TickUnitMilliseconds): Int =
      this.tickMillis.compareTo(other.tickMillis)

  override fun serialize(): String = this.tickMillis.toString()

  override fun deserialize(str: String): TickUnitMilliseconds = TickUnitMilliseconds(str.toLong())

  override fun toString(): String = "${this.tickMillis}ms"

  override fun equals(other: Any?): Boolean =
      if (other is TickUnitMilliseconds) this.tickMillis == other.tickMillis
      else super.equals(other)

  override fun hashCode(): Int = this.tickMillis.hashCode()
}
