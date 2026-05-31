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

package tools.aqua.stars.sumo.mutants

import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import tools.aqua.stars.sumo.Autopilot
import tools.aqua.stars.sumo.Mutant

/** AUTO-GENERATED: registry of all Autopilot mutants. */
object AutopilotMutants {
  /** Mapping from index to the corresponding mutant class. */
  val byIndex: Map<Int, KClass<out Mutant>> =
      mapOf(
          1 to AutopilotMutant1::class,
          2 to AutopilotMutant2::class,
          3 to AutopilotMutant3::class,
          4 to AutopilotMutant4::class,
          5 to AutopilotMutant5::class,
          6 to AutopilotMutant6::class,
          7 to AutopilotMutant7::class,
          8 to AutopilotMutant8::class,
          9 to AutopilotMutant9::class,
          10 to AutopilotMutant10::class,
          11 to AutopilotMutant11::class,
          12 to AutopilotMutant12::class,
          13 to AutopilotMutant13::class,
          14 to AutopilotMutant14::class,
          15 to AutopilotMutant15::class,
          16 to AutopilotMutant16::class,
          17 to AutopilotMutant17::class,
          18 to AutopilotMutant18::class,
          19 to AutopilotMutant19::class,
          20 to AutopilotMutant20::class,
          21 to AutopilotMutant21::class,
          22 to AutopilotMutant22::class,
          23 to AutopilotMutant23::class,
          24 to AutopilotMutant24::class,
          25 to AutopilotMutant25::class,
          26 to AutopilotMutant26::class,
          27 to AutopilotMutant27::class,
          28 to AutopilotMutant28::class,
          29 to AutopilotMutant29::class,
          30 to AutopilotMutant30::class)

  /**
   * Creates a new instance of the [Mutant] at the given [index].
   *
   * @param index The index of the [Mutant] that should be instantiated.
   * @return The instantiated Mutant.
   */
  fun create(index: Int): Mutant {
    if (index == -1) {
      return Autopilot()
    }
    return byIndex[index]?.createInstance() ?: error("No mutant for index=$index")
  }

  /**
   * Creates a list of new instances of [Mutant] in the range [from]..[toInclusive].
   *
   * @param from The start index of the range (inclusive).
   * @param toInclusive The end index of the range (inclusive).
   * @return A list of new instances of [Mutant] in the range [from]..[toInclusive].
   */
  fun createRange(from: Int, toInclusive: Int): List<Mutant> =
      (from..toInclusive).map { create(it) }
}
