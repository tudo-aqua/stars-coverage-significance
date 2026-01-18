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

import kotlinx.serialization.json.Json
import tools.aqua.stars.core.serialization.tsc.SerializableTSCNode
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCEntry

/** JSON configuration for serialization and deserialization. */
val jsonConfiguration: Json = Json {
  prettyPrint = true
  isLenient = true
}

/**
 * Extension function to convert a [SerializableTSCNode] to its JSON string representation.
 *
 * @return JSON string representation of the [SerializableTSCNode].
 */
fun SerializableTSCNode.getJsonString(): String = jsonConfiguration.encodeToString(this)

fun TSC<
    *,
    *,
    *,
    *,
>
    .toTSCEntry() =
    TSCEntry(
        hash = this.hashCode().toString(),
        tscJson = SerializableTSCNode(this.rootNode).getJsonString(),
        possibleTSCInstancesCount = this.instanceCount.toInt())
