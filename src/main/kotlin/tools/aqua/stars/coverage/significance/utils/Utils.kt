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

package tools.aqua.stars.coverage.significance.utils

import java.io.File
import kotlin.io.path.Path
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.intLiteral
import tools.aqua.stars.core.serialization.tsc.SerializableTSCNode
import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.coverage.significance.COLLISION_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.EXPORT_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.SCENARIO_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.db.dataclasses.TSCEntry

/** JSON configuration for serialization and deserialization. */
val jsonConfiguration: Json = Json { isLenient = true }

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
 * Converts a boolean column to an integer column.
 *
 * @param col The boolean column to convert.
 * @return The integer column representing the boolean values.
 */
fun boolToInt(col: Column<Boolean>): ExpressionWithColumnType<Int> =
    Case().When(col eq true, intLiteral(1)).Else(intLiteral(0))

/**
 * Returns a new list containing every nth element from the original list, starting at the specified
 * offset.
 *
 * @param T Type of the list elements.
 * @param n The interval at which elements are selected. Must be greater than 0.
 * @param offset The starting index from which to begin selecting elements. Must be in the range 0
 *   until n.
 * @return A list containing every nth element from the original list, starting at the specified
 *   offset.
 * @throws IllegalArgumentException if the value of n is less than or equal to 0, or if the offset
 *   is not in the range 0 until n.
 */
fun <T> List<T>.everyNth(n: Int, offset: Int = 0): List<T> {
  require(n > 0) { "n must be > 0" }
  require(offset in 0 until n) { "offset must be in 0 until n (was $offset)" }

  if (isEmpty()) return emptyList()

  val outSize = ((size - 1 - offset).coerceAtLeast(-1) / n) + 1
  val result = ArrayList<T>(outSize.coerceAtLeast(0))

  var i = offset
  while (i < size) {
    result.add(this[i])
    i += n
  }
  return result
}

/**
 * Converts a [TSC] to a [TSCEntry].
 *
 * @return Converted [TSCEntry].
 * @receiver TSC The [TSC] to convert.
 */
fun TSC<
    *,
    *,
    *,
    *,
>
    .toTSCEntry() =
    TSCEntry(
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
 * Retrieves the base key of a file by removing known extensions.
 *
 * @return String The base key of the file.
 * @receiver File The file from which to extract the base key.
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

/**
 * Generates a unique vehicle ID based on the provided parameters.
 *
 * @param vehicleType Type of the vehicle.
 * @param row Row number.
 * @param lane Lane number.
 * @param scenarioId Identifier for the scenario.
 * @param vehiclePrefix Prefix for the vehicle ID (default is "veh").
 * @return Generated unique vehicle ID.
 */
fun getVehicleId(
    vehicleType: String,
    row: Int,
    lane: Int,
    scenarioId: String,
    vehiclePrefix: String = "veh",
): String = "${vehiclePrefix}_${vehicleType}_[${row}][${lane}]_in_${scenarioId}"
