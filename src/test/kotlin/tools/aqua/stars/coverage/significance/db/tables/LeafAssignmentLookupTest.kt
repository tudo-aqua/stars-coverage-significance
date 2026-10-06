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

package tools.aqua.stars.coverage.significance.db.tables

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.LeafAssignmentLookup

/**
 * Tests for [LeafAssignmentLookup]'s lookup math - the in-memory replacement for joining against
 * the `decision_tree_leaf_assignments` view per chunk (see its KDoc on
 * [MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations] for why). Fixtures are built
 * directly via the constructor, bypassing [LeafAssignmentLookup.load] (and so the database)
 * entirely - only the id-to-index arithmetic and the sentinel handling are under test here.
 */
class LeafAssignmentLookupTest {

  private val chunkSize = LEAF_ASSIGNMENT_CHUNK_SIZE.toLong()

  @Test
  fun `looks up a leaf id at the start of a chunk`() {
    val chunk = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    chunk[0] = 7
    val lookup = LeafAssignmentLookup(mapOf(0L to chunk))
    assertEquals(7, lookup.leafNodeIdOrNull(0L))
  }

  @Test
  fun `looks up a leaf id in the middle of a chunk`() {
    val chunk = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    chunk[42] = 9
    val lookup = LeafAssignmentLookup(mapOf(0L to chunk))
    assertEquals(9, lookup.leafNodeIdOrNull(42L))
  }

  @Test
  fun `looks up a leaf id at the start of a later, non-zero-based chunk`() {
    val firstId = chunkSize * 3
    val chunk = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    chunk[5] = 11
    val lookup = LeafAssignmentLookup(mapOf(firstId to chunk))
    assertEquals(11, lookup.leafNodeIdOrNull(firstId + 5))
  }

  @Test
  fun `an id exactly at the end of a chunk resolves within that chunk, not the next`() {
    val chunk0 = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    chunk0[LEAF_ASSIGNMENT_CHUNK_SIZE - 1] = 3
    val chunk1 = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    chunk1[0] = 4
    val lookup = LeafAssignmentLookup(mapOf(0L to chunk0, chunkSize to chunk1))

    assertEquals(3, lookup.leafNodeIdOrNull(chunkSize - 1))
    assertEquals(4, lookup.leafNodeIdOrNull(chunkSize))
  }

  @Test
  fun `the NO_LEAF sentinel reads back as a gap, not as a real leaf id`() {
    val chunk = ShortArray(LEAF_ASSIGNMENT_CHUNK_SIZE) { LeafAssignmentLookup.NO_LEAF }
    val lookup = LeafAssignmentLookup(mapOf(0L to chunk))
    assertNull(lookup.leafNodeIdOrNull(123L))
  }

  @Test
  fun `an id whose chunk was never loaded resolves to null rather than throwing`() {
    val lookup = LeafAssignmentLookup(emptyMap())
    assertNull(lookup.leafNodeIdOrNull(999_999L))
  }
}
