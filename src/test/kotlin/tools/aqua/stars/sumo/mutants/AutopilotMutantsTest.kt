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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.aqua.stars.sumo.Autopilot

/**
 * Verifies that every mutant registered in [AutopilotMutants] can be loaded: the generated registry
 * must cover exactly the generated mutant sources, and [AutopilotMutants.create] must be able to
 * instantiate each entry (which also runs each mutant's constructor, e.g. `AiPilot` loading its
 * network weights from the classpath).
 *
 * See [MutantSimulationTest] for driving each mutant in a live simulation.
 */
class AutopilotMutantsTest {

  /**
   * `scripts/create_mutants.sh` writes one `AutopilotMutant<N>.kt` per mutant and registers it
   * under index `N`; a file it forgot to register would silently never be evaluated.
   */
  @Test
  fun `every generated mutant source file is registered under its own number`() {
    val generatedNumbers =
        checkNotNull(MUTANTS_SOURCE_DIR.listFiles()) { "$MUTANTS_SOURCE_DIR is not a directory" }
            .mapNotNull { GENERATED_MUTANT_FILE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
            .toSortedSet()

    assertTrue(generatedNumbers.isNotEmpty(), "no generated mutants found in $MUTANTS_SOURCE_DIR")
    generatedNumbers.forEach { number ->
      assertEquals(
          "AutopilotMutant$number",
          AutopilotMutants.byIndex[number]?.simpleName,
          "AutopilotMutant$number.kt is not registered under index $number")
    }
  }

  /**
   * Creates the original [Autopilot] (index `-1`) and every mutant in [AutopilotMutants.byIndex].
   */
  @TestFactory
  fun `every mutant can be instantiated`(): List<DynamicTest> =
      listOf(
          DynamicTest.dynamicTest("original Autopilot (index -1)") {
            assertIs<Autopilot>(AutopilotMutants.create(-1))
          }) +
          AutopilotMutants.byIndex.toSortedMap().map { (index, mutantClass) ->
            DynamicTest.dynamicTest("${mutantClass.simpleName} (index $index)") {
              val mutant = AutopilotMutants.create(index)
              assertEquals(mutantClass, mutant::class)
            }
          }

  private companion object {
    /** Source directory the generated mutants are written to (tests run from the project root). */
    val MUTANTS_SOURCE_DIR = File("src/main/kotlin/tools/aqua/stars/sumo/mutants")

    /** File name of a generated mutant, capturing its index. */
    val GENERATED_MUTANT_FILE = Regex("""AutopilotMutant(\d+)\.kt""")
  }
}
