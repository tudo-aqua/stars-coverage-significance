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

package tools.aqua.stars.coverage.significance.manualTesting

import java.util.UUID

/**
 * Data class representing a mutant.
 *
 * @property id Unique identifier of the mutant.
 * @property initialAwareness Initial awareness of the mutant.
 * @property headwayErrorCoefficient Headway error coefficient of the mutant.
 * @property headwayChangePerceptionThreshold Headway change perception threshold of the mutant.
 * @property speedDifferenceErrorCoefficient Speed difference error coefficient of the mutant.
 * @property speedDifferenceChangePerceptionThreshold Speed difference change perception threshold
 *   of the mutant.
 * @property errorNoiseIntensityCoefficient Error noise intensity coefficient of the mutant.
 * @property maximalReactionTime Maximal reaction time of the mutant.
 * @property maxSpeed Maximum speed of the mutant.
 * @property speedFactor Speed factor of the mutant.
 * @property speedDeviation Speed deviation of the mutant.
 * @property sigma Sigma of the mutant.
 * @property tau Tau of the mutant.
 * @property minGap Minimum gap of the mutant.
 * @property lcAssertive Lane change assertiveness of the mutant.
 * @property lcSpeedGain Lane change speed gain of the mutant.
 * @property lcCooperative Lane change cooperativeness of the mutant.
 */
data class Mutant(
    val id: UUID = UUID.randomUUID(),
    val initialAwareness: Double = 1.0,
    val headwayErrorCoefficient: Double = 0.75,
    val headwayChangePerceptionThreshold: Double = 0.05,
    val speedDifferenceErrorCoefficient: Double = 0.15,
    val speedDifferenceChangePerceptionThreshold: Double = 0.05,
    val errorNoiseIntensityCoefficient: Double = 0.2,
    val maximalReactionTime: Double = 2.5,
    val maxSpeed: Double = 55.55,
    val speedFactor: Double = 1.0,
    val speedDeviation: Double = 0.0,
    val sigma: Double = 0.5,
    val tau: Double = 1.0,
    val minGap: Double = 2.5,
    val lcAssertive: Double = 1.0,
    val lcSpeedGain: Double = 1.0,
    val lcCooperative: Double = 1.0,
)
