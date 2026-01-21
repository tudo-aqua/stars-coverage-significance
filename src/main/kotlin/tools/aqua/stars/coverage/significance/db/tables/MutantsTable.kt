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

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import tools.aqua.stars.coverage.significance.db.dataclasses.MutantEntry

/**
 * Table object for the [MutantEntry] data class.
 *
 * @property createdAt Timestamp of when the mutant was created.
 * @property mutantKey Unique key identifying the mutant.
 * @property c1Level Level of bundle C1.
 * @property c2Level Level of bundle C2.
 * @property c3Level Level of bundle C3.
 * @property c4Level Level of bundle C4.
 * @property c5Level Level of bundle C5.
 * @property headwayErrorCoefficient Headway error coefficient (C1).
 * @property speedDifferenceErrorCoefficient Speed difference error coefficient (C1).
 * @property headwayChangePerceptionThreshold Headway change perception threshold (C2).
 * @property speedDifferenceChangePerceptionThreshold Speed difference change perception threshold
 *   (C2).
 * @property maximalReactionTime Maximal reaction time (C2).
 * @property errorNoiseIntensityCoefficient Error noise intensity coefficient (C3).
 * @property errorTimeScaleCoefficient Error time scale coefficient (C3).
 * @property initialAwareness Initial awareness (C4).
 * @property minAwareness Minimum awareness (C4).
 * @property speedFactor Speed factor (C5).
 * @property lcAssertive Lane change assertiveness (C5).
 * @property lcSpeedGain Lane change speed gain (C5).
 * @property lcCooperative Lane change cooperativeness (C5).
 */
object MutantsTable : UUIDTable("mutants") {
  val createdAt = timestamp("created_at")

  val mutantKey = varchar("mutant_key", 32).uniqueIndex()

  // Bundle levels
  val c1Level = integer("c1_level")
  val c2Level = integer("c2_level")
  val c3Level = integer("c3_level")
  val c4Level = integer("c4_level")
  val c5Level = integer("c5_level")

  // Bundle C1
  val headwayErrorCoefficient = double("headway_error_coefficient")
  val speedDifferenceErrorCoefficient = double("speed_difference_error_coefficient")

  // Bundle C2
  val headwayChangePerceptionThreshold = double("headway_change_perception_threshold")
  val speedDifferenceChangePerceptionThreshold =
      double("speed_difference_change_perception_threshold")
  val maximalReactionTime = double("maximal_reaction_time")

  // Bundle C3
  val errorNoiseIntensityCoefficient = double("error_noise_intensity_coefficient")
  val errorTimeScaleCoefficient = double("error_time_scale_coefficient")

  // Bundle C4
  val initialAwareness = double("initial_awareness")
  val minAwareness = double("min_awareness")

  // Bundle C5
  val speedFactor = double("speed_factor")
  val lcAssertive = double("lc_assertive")
  val lcSpeedGain = double("lc_speed_gain")
  val lcCooperative = double("lc_cooperative")
}
