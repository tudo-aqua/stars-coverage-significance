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

import java.io.File
import kotlin.io.path.Path
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

/**
 * Splits the list into a specified number of buckets.
 *
 * @param T Type of the list elements.
 * @param bucketCount Number of buckets to split the list into.
 * @return List of buckets.
 */
fun <T> List<T>.buckets(bucketCount: Int): List<List<T>> = run {
  val total = this.size
  val base = total / bucketCount
  val rem = total % bucketCount

  var start = 0
  (0 until bucketCount)
      .map { i ->
        val size = base + if (i < rem) 1 else 0
        val end = start + size
        val bucket = this.subList(start, end)
        start = end
        bucket
      }
      .filter { it.isNotEmpty() }
}

/**
 * Converts a [TSC] to a [TSCEntry].
 *
 * @return Converted [TSCEntry].
 */
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

/**
 * Lists all files in a directory, sorted by name.
 *
 * @param dir Directory path.
 * @return List of files sorted by name.
 */
fun listSortedFiles(dir: String): List<File> =
    Path(dir).toFile().listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()

/**
 * Computes a stable key shared across scenario/export/collision files. Adjust suffix stripping here
 * if your file naming differs.
 */
fun File.baseKey(): String {
  val n = name
  return when {
    n.endsWith(SCENARIO_FILE_EXTENSION) -> n.removeSuffix(SCENARIO_FILE_EXTENSION)
    n.endsWith(EXPORT_FILE_EXTENSION) -> n.removeSuffix(EXPORT_FILE_EXTENSION)
    n.endsWith(COLLISION_FILE_EXTENSION) -> n.removeSuffix(COLLISION_FILE_EXTENSION)
    n.endsWith(".xml") -> n.removeSuffix(".xml")
    else -> n
  }
}
