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

package tools.aqua.stars.coverage.significance

import java.util.*
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.db
import tools.aqua.stars.coverage.significance.db.repositories.DistinctMutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficLongTailRepository
import tools.aqua.stars.coverage.significance.db.repositories.HighwayTrafficScenariosRepository
import tools.aqua.stars.coverage.significance.db.repositories.MutantsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCInstancesRepository
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildFailedMonitorMapping
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildFailedMutantsMapping
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTSCInstanceChangeData
import tools.aqua.stars.coverage.significance.db.tables.MetricFailedMonitorsTable.buildTSCInstanceTransitions
import tools.aqua.stars.coverage.significance.postEvaluation.TSCInstanceTransitionAnalysis
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.*
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceChangeData
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceTransition
import tools.aqua.stars.coverage.significance.utils.MonitorViolation
import tools.aqua.stars.coverage.significance.utils.MonitorViolation.Companion.buildMonitorCombinations

// region Mutants
// ----------------------------------------------------------------------------------------------------
/** All Mutant IDs. */
val mutantIds: List<UUID> by lazy { db { MutantsRepository.getAllIds() } }

/** All behavioral distinct Mutant IDs. */
val distinctMutantIds: List<UUID> by lazy { db { DistinctMutantsRepository.getAllIds() } }
// ----------------------------------------------------------------------------------------------------
// endregion

// region Mutant Failures
// ----------------------------------------------------------------------------------------------------
/** Mutant failures as they come from the DB. Still contains entries with no failures. */
val mutantFailuresFromDB: List<MutantFailure> by lazy { db { buildFailedMutantsMapping() } }

/** All mutant failures with at least one failing monitor. */
val mutantFailuresFiltered: List<MutantFailure> by lazy {
  mutantFailuresFromDB.filter { it.monitorBitmask > 0 }
}

/** Distinct mutant failures with at least one failing monitor. */
val distinctMutantFailuresFiltered: List<MutantFailure> by lazy {
  db { mutantFailuresFiltered.filter { it.mutantID in distinctMutantIds } }
}
// ----------------------------------------------------------------------------------------------------
// endregion

// region Scenarios
// ----------------------------------------------------------------------------------------------------
/** All possible scenario instances based on the TSC. */
val allScenarioInstances: List<ScenarioIdAndJSON> by lazy {
  db { TSCInstancesRepository.getAllScenariosWithJSON() }
}
// ----------------------------------------------------------------------------------------------------
// endregion

// region Random Highway Traffic
// ----------------------------------------------------------------------------------------------------
/** Data from the random highway experiment from DB. */
val randomTrafficAnalysis: List<HighwayTrafficScenarioInstanceId> by lazy {
  db { HighwayTrafficScenariosRepository.getInstanceIds() }
}

/** Distribution of scenario instances from the random highway experiment. */
val longtailDistribution by lazy {
  db { HighwayTrafficLongTailRepository.getAll().sortedByDescending { it.longTailValue } }
}
// ----------------------------------------------------------------------------------------------------
// endregion

/** Mapping of scenario failures to monitors. */
val failedMonitorMapping: List<ScenarioFailure> by lazy { db { buildFailedMonitorMapping() } }

/**
 * Per-(mutant, scenarioConfiguration) TSC instance change times and accumulated monitor failures.
 */
val tscInstanceChangeData: List<TSCInstanceChangeData> by lazy {
  db { buildTSCInstanceChangeData(tsc()) }
}

/** Aggregated (from → to) TSC-instance transition counts, with per-monitor breakdown. */
val tscInstanceTransitions: List<TSCInstanceTransition> by lazy {
  db { buildTSCInstanceTransitions(tsc()) }
}

/** All possible combinations of monitors. */
val monitorCombinations: List<Set<MonitorViolation>> by lazy { db { buildMonitorCombinations() } }

/** All scenario IDs. */
val scenarioIds: List<UUID> by lazy {
  db { TSCInstancesRepository.getAllScenariosWithJSON().map { it.scenarioInstanceId } }
}

/** The size of the TSC. */
val TSC_SIZE = tsc().instanceCount.toInt()

/** The number of repetitions of the evaluation. */
val REPETITIONS: Int = 100
val TEST_SUITE_SIZE: Int = 160

/** Post-evaluation of the coverage significance evaluation. */
fun main() {
  DbBootstrap.connectAndCreateSchema(DbBootstrap.DbConfig(port = 5432))

  //  LongTailDistributionPostEvaluation.evaluate()

  /**
   * Calculate the time until a TSCInstance changes for each mutant x scenario pair. Calculate the
   * failed monitors in the time spans from above.
   */
  //  TSCInstanceChangeAnalysis.evaluate()

  /** Build transition automaton between TSC instances and render heatmaps. */
  TSCInstanceTransitionAnalysis.evaluate()

  /** Populate the database with longtail distribution from random highway traffic */
  //  PopulateHighwayTrafficLongTailTable.populate()

  /**
   * Baseline comparing TSC approach with purely random draw and draw from generated starting
   * scenarios
   */
  //  BaselinePostEvaluation.evaluate()
  //  BaselinePostEvaluation2.evaluate()

  /** Evaluate longtail distribution from random highway traffic */
  //  HighwayTrafficAnalysis.evaluate()

  /** Evaluate how many mutants have been killed by different values for scenario coverage. */
  //    MutantKillingPostEvaluation.evaluate()

  /** Plot with long-tail and scatter-plot of how many mutants are killed by each monitor * */
  //  MutantsKilledByMonitorsPerScenario.evaluate()

  /** Analyze redundant monitors */
  //  RedundantMonitorPostEvaluation.evaluate()

  /** Evaluate how many mutants can be killed by each monitor. */
  //  CountOfMutantsKilledPerMonitor.evaluate()

  /** Plot for each mutant how many scenarios are capable of killing it. */
  //  CountOfScenariosKillingAMutantPerMutantPostEvaluation.evaluate()

  /** Heatmap of how many mutants are killed by a scenario that are not killed by the other */
  //  ScenarioByScenarioCrossTable.evaluate()

  /** Heatmap of which mutants are killed by which scenario */
  //  ScenarioByMonitorCrossTable.evaluate()

  println("Finished!")
}

private operator fun List<Int>.times(other: Int) = this.map { it * other }
