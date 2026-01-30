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

package tools.aqua.stars.coverage.significance.db.dataclasses

import java.time.Instant
import java.util.UUID

/**
 * Data class representing a mutant entry in the database.
 *
 * @property id Unique identifier of the mutant entry.
 * @property createdAt Timestamp of when the mutant entry was created.
 * @property mutantKey Unique key representing the mutant configuration.
 * @property c1Level Level of parameter c1.
 * @property c2Level Level of parameter c2.
 * @property c3Level Level of parameter c3.
 * @property c4Level Level of parameter c4.
 * @property c5Level Level of parameter c5.
 * @property headwayErrorCoefficient Coefficient for headway error.
 * @property speedDifferenceErrorCoefficient Coefficient for speed difference error.
 * @property headwayChangePerceptionThreshold Threshold for perceiving changes in headway.
 * @property speedDifferenceChangePerceptionThreshold Threshold for perceiving changes in speed
 *   difference.
 * @property maximalReactionTime Maximal reaction time of the driver model.
 * @property maxSpeed Maximum speed of the driver model.
 * @property errorNoiseIntensityCoefficient Coefficient for the intensity of error noise.
 * @property errorTimeScaleCoefficient Coefficient for the time scale of error noise.
 * @property initialAwareness Initial awareness level of the driver model.
 * @property speedFactor Speed factor of the driver model.
 * @property speedDeviation Speed deviation of the driver model.
 * @property sigma Sigma of the driver model.
 * @property tau Tau of the driver model.
 * @property minGap Minimum gap of the driver model.
 * @property lcAssertive Level of assertiveness in lane changing.
 * @property lcSpeedGain Speed gain factor in lane changing.
 * @property lcCooperative Level of cooperativeness in lane changing.
 */
data class MutantEntry(
    val id: UUID? = null,
    val createdAt: Instant,
    val mutantKey: String,
    val c1Level: Int,
    val c2Level: Int,
    val c3Level: Int,
    val c4Level: Int,
    val c5Level: Int,
    val initialAwareness: Double = 1.0,
    val minAwareness: Double = 0.1,
    val headwayErrorCoefficient: Double = 0.75,
    val headwayChangePerceptionThreshold: Double = 0.1,
    val speedDifferenceErrorCoefficient: Double = 0.15,
    val speedDifferenceChangePerceptionThreshold: Double = 0.1,
    val errorNoiseIntensityCoefficient: Double = 0.2,
    val errorTimeScaleCoefficient: Double = 100.0,
    val maximalReactionTime: Double = 2.5,
    val maxSpeed: Double = 55.55,
    val speedFactor: Double = 1.0,
    val speedDeviation: Double = 0.1,
    val sigma: Double = 0.5,
    val tau: Double = 1.0,
    val minGap: Double = 2.5,
    val lcAssertive: Double = 1.0,
    val lcSpeedGain: Double = 1.0,
    val lcCooperative: Double = 1.0,
)
