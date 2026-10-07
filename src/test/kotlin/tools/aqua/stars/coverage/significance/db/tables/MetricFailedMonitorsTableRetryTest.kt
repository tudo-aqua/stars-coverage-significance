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

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.isTransientConnectionTermination
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.sqlState
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.withTransientConnectionRetry

/**
 * Tests for the cause-chain SQLSTATE lookup and retry-on-transient-disconnect logic that
 * [MetricFailedMonitorsTable.buildTickWiseNextTickMonitorViolations] uses to survive a connection
 * being severed by something other than the query itself (e.g. `SQLSTATE 57P01`, PostgreSQL's
 * admin_shutdown code, raised when the server closes every open connection on an administrative
 * command such as a container restart).
 */
class MetricFailedMonitorsTableRetryTest {

  // ----------------------------------------------------------------------------------- sqlState()

  @Test
  fun `sqlState finds the state on a bare SQLException`() {
    assertEquals("57P01", SQLException("boom", "57P01").sqlState())
  }

  @Test
  fun `sqlState walks the cause chain through non-SQL wrapper exceptions`() {
    val wrapped = RuntimeException("wrapped", SQLException("boom", "57P01"))
    assertEquals("57P01", wrapped.sqlState())
  }

  @Test
  fun `sqlState walks through multiple layers of wrapping`() {
    val inner = SQLException("boom", "57P01")
    val middle = RuntimeException("middle", inner)
    val outer = IllegalStateException("outer", middle)
    assertEquals("57P01", outer.sqlState())
  }

  @Test
  fun `sqlState skips an SQLException whose own state is null and keeps looking deeper`() {
    val inner = SQLException("boom", "57P01")
    val outer = SQLException("no state here", null as String?, inner)
    assertEquals("57P01", outer.sqlState())
  }

  @Test
  fun `sqlState returns null when no SQLException is anywhere in the chain`() {
    assertNull(RuntimeException("no sql here").sqlState())
  }

  @Test
  fun `sqlState on a null receiver returns null`() {
    val none: Throwable? = null
    assertNull(none.sqlState())
  }

  // ------------------------------------------------------------- isTransientConnectionTermination

  @Test
  fun `admin_shutdown is a transient connection termination`() {
    assertTrue(SQLException("terminated", "57P01").isTransientConnectionTermination())
  }

  @Test
  fun `query_canceled is also class 57 and counts as transient`() {
    assertTrue(SQLException("canceled", "57014").isTransientConnectionTermination())
  }

  @Test
  fun `a genuine data error is not treated as transient`() {
    assertFalse(SQLException("duplicate key", "23505").isTransientConnectionTermination())
  }

  @Test
  fun `an exception with no SQLSTATE anywhere is not treated as transient`() {
    assertFalse(RuntimeException("unrelated").isTransientConnectionTermination())
  }

  // ---------------------------------------------------------------------
  // withTransientConnectionRetry

  @Test
  fun `succeeds immediately without retrying when the block succeeds on the first try`() {
    var calls = 0
    val result =
        withTransientConnectionRetry("test chunk", baseDelayMs = 1L) {
          calls++
          "ok"
        }
    assertEquals("ok", result)
    assertEquals(1, calls)
  }

  @Test
  fun `retries a transient failure and returns the eventual success`() {
    var calls = 0
    val result =
        withTransientConnectionRetry("test chunk", maxAttempts = 3, baseDelayMs = 1L) {
          calls++
          if (calls < 3) throw SQLException("terminated", "57P01")
          "ok on attempt $calls"
        }
    assertEquals("ok on attempt 3", result)
    assertEquals(3, calls)
  }

  @Test
  fun `gives up after maxAttempts and rethrows the last transient failure`() {
    var calls = 0
    val thrown =
        assertFailsWith<SQLException> {
          withTransientConnectionRetry("test chunk", maxAttempts = 3, baseDelayMs = 1L) {
            calls++
            throw SQLException("terminated #$calls", "57P01")
          }
        }
    assertEquals(3, calls)
    assertEquals("terminated #3", thrown.message)
  }

  @Test
  fun `does not retry a non-transient failure - it propagates on the first attempt`() {
    var calls = 0
    assertFailsWith<SQLException> {
      withTransientConnectionRetry("test chunk", maxAttempts = 3, baseDelayMs = 1L) {
        calls++
        throw SQLException("duplicate key", "23505")
      }
    }
    assertEquals(1, calls)
  }
}
