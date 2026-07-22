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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import tools.aqua.stars.coverage.significance.db.dataclasses.DecisionTreeMutantSplitEntry
import tools.aqua.stars.coverage.significance.db.dataclasses.DecisionTreeRunEntry
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeMutantSplitsTable
import tools.aqua.stars.coverage.significance.db.tables.DecisionTreeRunsTable

/** Repository for querying [DecisionTreeRunsTable] and [DecisionTreeMutantSplitsTable]. */
object DecisionTreeRunsRepository {

  /**
   * Retrieves all decision tree runs, ordered by ascending ID.
   *
   * @return All [DecisionTreeRunEntry]s.
   */
  fun getAll(): List<DecisionTreeRunEntry> = transaction {
    DecisionTreeRunsTable.selectAll().orderBy(DecisionTreeRunsTable.id).map { it.toEntry() }
  }

  /**
   * Retrieves a single decision tree run by its unique identifier.
   *
   * @param id Unique identifier of the decision tree run.
   * @return The corresponding [DecisionTreeRunEntry], or `null` if not found.
   */
  fun getById(id: Int): DecisionTreeRunEntry? = transaction {
    DecisionTreeRunsTable.selectAll()
        .where { DecisionTreeRunsTable.id eq id }
        .limit(1)
        .singleOrNull()
        ?.toEntry()
  }

  /**
   * Retrieves all mutant split entries recorded for the given decision tree run.
   *
   * @param runId Unique identifier of the decision tree run.
   * @return All [DecisionTreeMutantSplitEntry]s belonging to [runId].
   */
  fun getSplitsForRun(runId: Int): List<DecisionTreeMutantSplitEntry> = transaction {
    DecisionTreeMutantSplitsTable.selectAll()
        .where { DecisionTreeMutantSplitsTable.runId eq runId }
        .map {
          DecisionTreeMutantSplitEntry(
              runId = it[DecisionTreeMutantSplitsTable.runId].value,
              mutantId = it[DecisionTreeMutantSplitsTable.mutantId],
              trainedOn = it[DecisionTreeMutantSplitsTable.trainedOn],
          )
        }
  }

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

  /**
   * Converts a [ResultRow] to a [DecisionTreeRunEntry].
   *
   * @return The corresponding [DecisionTreeRunEntry].
   */
  private fun ResultRow.toEntry(): DecisionTreeRunEntry =
      DecisionTreeRunEntry(
          id = this[DecisionTreeRunsTable.id].value,
          createdAt = this[DecisionTreeRunsTable.createdAt],
          trainFraction = this[DecisionTreeRunsTable.trainFraction],
          seed = this[DecisionTreeRunsTable.seed],
          nTrainMutants = this[DecisionTreeRunsTable.nTrainMutants],
          nTestMutants = this[DecisionTreeRunsTable.nTestMutants],
          logText = this[DecisionTreeRunsTable.logText],
          dotSource = this[DecisionTreeRunsTable.dotSource],
          featEgoManeuver = this[DecisionTreeRunsTable.featEgoManeuver],
          featEgoSpeed = this[DecisionTreeRunsTable.featEgoSpeed],
          featEgoAccel = this[DecisionTreeRunsTable.featEgoAccel],
          featEgoPosition = this[DecisionTreeRunsTable.featEgoPosition],
          featDistances = this[DecisionTreeRunsTable.featDistances],
          featNeighborKinematics = this[DecisionTreeRunsTable.featNeighborKinematics],
          featTimeGaps = this[DecisionTreeRunsTable.featTimeGaps],
          nTrials = this[DecisionTreeRunsTable.nTrials],
          maxLeavesBound = this[DecisionTreeRunsTable.maxLeavesBound],
          classWeight = this[DecisionTreeRunsTable.classWeight],
          scalePosWeight = this[DecisionTreeRunsTable.scalePosWeight],
          hpNumLeaves = this[DecisionTreeRunsTable.hpNumLeaves],
          hpMaxDepth = this[DecisionTreeRunsTable.hpMaxDepth],
          hpMinChildSamples = this[DecisionTreeRunsTable.hpMinChildSamples],
          hpMinSplitGain = this[DecisionTreeRunsTable.hpMinSplitGain],
          tuningRocAuc = this[DecisionTreeRunsTable.tuningRocAuc],
          learnedNumLeaves = this[DecisionTreeRunsTable.learnedNumLeaves],
          learnedMaxDepth = this[DecisionTreeRunsTable.learnedMaxDepth],
          trainAccuracy = this[DecisionTreeRunsTable.trainAccuracy],
          testAccuracy = this[DecisionTreeRunsTable.testAccuracy],
      )
}
