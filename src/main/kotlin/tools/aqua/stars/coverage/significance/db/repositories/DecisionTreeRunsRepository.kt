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

package tools.aqua.stars.coverage.significance.db.repositories

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeMutantSplitsTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable

/** Repository for querying [DecisionTreeRunsTable] and [DecisionTreeMutantSplitsTable]. */
object DecisionTreeRunsRepository {

  /**
   * Returns the ID of the most recent decision tree run where the full dataset was used for
   * training (`train_fraction = 1.0`), or `null` if no such run exists.
   */
  fun getLatestFullRunId(): EntityID<Int>? = transaction {
    DecisionTreeRunsTable.selectAll()
        .where { DecisionTreeRunsTable.trainFraction eq 1.0 }
        .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.get(DecisionTreeRunsTable.id)
  }

  /**
   * Returns all test-set mutant IDs (`trained_on = false`) from the most recent decision tree run,
   * or `null` if no run has been recorded yet.
   *
   * @return List of mutant IDs in the test set of the latest run, or `null` if no run exists.
   */
  fun getLatestRunTestMutantIds(): List<Int>? = transaction {
    val latestRunId =
        DecisionTreeRunsTable.selectAll()
            .orderBy(DecisionTreeRunsTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(DecisionTreeRunsTable.id) ?: return@transaction null

    DecisionTreeMutantSplitsTable.selectAll()
        .where {
          (DecisionTreeMutantSplitsTable.runId eq latestRunId) and
              (DecisionTreeMutantSplitsTable.trainedOn eq false)
        }
        .map { it[DecisionTreeMutantSplitsTable.mutantId] }
  }
}
