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

package tools.aqua.stars.data.sumo.dataclasses.staticData

/**
 * Projection definition as encoded by SUMO in `<location projParameter="...">`.
 *
 * SUMO uses:
 * - [None] when `projParameter="!"` (no projection defined)
 * - [Proj4] for a proj/proj.4 style parameter list, e.g. `+proj=utm +zone=32 +datum=WGS84 ...`
 */
sealed interface Projection {

  /** Represents the special SUMO value `projParameter="!"`, meaning no projection is defined. */
  data object None : Projection

  /**
   * A PROJ/proj.4 projection definition represented as tokens.
   *
   * Tokens are usually space-separated and start with '+'.
   *
   * @property tokens Ordered list of tokens as they appear in the `projParameter` string.
   */
  data class Proj4(val tokens: List<ProjToken>) : Projection
}

/**
 * A single token from a PROJ/proj.4 parameter string.
 *
 * Tokens are typically formatted as:
 * - `+key=value`
 * - `+flag` (no value)
 *
 * @property key Parameter key without leading '+'.
 * @property value Parameter value (or [ProjValue.Flag] if absent).
 */
data class ProjToken(val key: String, val value: ProjValue)

/** Value of a PROJ/proj.4 token. */
sealed interface ProjValue {

  /** Token without an explicit value, e.g. `+no_defs`. */
  data object Flag : ProjValue

  /**
   * Numeric token value, e.g. `+k=1` or `+x_0=500000`.
   *
   * @property number Parsed numeric value.
   */
  data class Number(val number: Double) : ProjValue

  /**
   * Text token value, e.g. `+proj=utm` or `+datum=WGS84`.
   *
   * @property text Raw text value.
   */
  data class Text(val text: String) : ProjValue
}
