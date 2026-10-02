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

package tools.aqua.stars.coverage.validation.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeLeafAssignmentsTable
import tools.aqua.stars.coverage.significance.db.tables.LEAF_ASSIGNMENT_CHUNK_SIZE

/**
 * The Python scripts write `decision_tree_leaf_assignment_chunks` and create the
 * `decision_tree_leaf_assignments` view themselves; they must use the same chunk size and view
 * definition as the Kotlin side, or ticks get assigned to the wrong ids.
 */
class LeafAssignmentScriptsConsistencyTest {

  /** Both writer scripts use [LEAF_ASSIGNMENT_CHUNK_SIZE]. */
  @Test
  fun `Test scripts use the Kotlin chunk size`() {
    val pythonValue = "%,d".format(LEAF_ASSIGNMENT_CHUNK_SIZE).replace(",", "_")
    for (script in listOf("scripts/decision_tree_g0.py", "scripts/label_new_ticks.py")) {
      assertTrue(
          File(script)
              .readText()
              .replace("\r\n", "\n")
              .contains("LEAF_ASSIGNMENT_CHUNK_SIZE = $pythonValue\n"),
          "$script does not use LEAF_ASSIGNMENT_CHUNK_SIZE = $pythonValue")
    }
  }

  /** `decision_tree_g0.py` creates the view with [DecisionTreeLeafAssignmentsTable.VIEW_QUERY]. */
  @Test
  fun `Test decision_tree_g0 creates the same view`() {
    assertTrue(
        File("scripts/decision_tree_g0.py")
            .readText()
            .replace("\r\n", "\n")
            .contains("\"\"\"${DecisionTreeLeafAssignmentsTable.VIEW_QUERY}\"\"\""),
        "scripts/decision_tree_g0.py does not contain DecisionTreeLeafAssignmentsTable.VIEW_QUERY")
  }
}
