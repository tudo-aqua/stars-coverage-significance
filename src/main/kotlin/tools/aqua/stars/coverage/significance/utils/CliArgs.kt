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

/** Command-line argument parsing utilities. */
object CliArgs {

  /**
   * Requires a string argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to retrieve.
   * @return The value of the required string argument.
   * @throws IllegalArgumentException if the argument is not found.
   */
  fun requireString(args: Array<String>, name: String): String {
    val prefix = "--$name="
    return args.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)
        ?: error("Missing required arg: $prefix<value>")
  }

  /**
   * Requires an Int argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to retrieve.
   * @return The value of the required Int argument.
   * @throws IllegalArgumentException if the argument is not found or is not a valid Int
   */
  fun requireInt(args: Array<String>, name: String): Int = requireString(args, name).toInt()

  /**
   * Requires a Long argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to retrieve.
   * @return The value of the required Long argument.
   * @throws IllegalArgumentException if the argument is not found or is not a valid Long
   */
  fun requireLong(args: Array<String>, name: String): Long = requireString(args, name).toLong()

  /**
   * Parses an optional Int argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to parse.
   * @return The parsed Int value, or `null` if the argument is not found or is not a valid Int.
   */
  fun optionalInt(args: Array<String>, name: String): Int? {
    val prefix = "--$name="
    return args.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.toIntOrNull()
  }

  /**
   * Parses an optional Double argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to parse.
   * @return The parsed Double value, or `null` if the argument is not found or is not a valid
   *   Double.
   */
  fun optionalDouble(args: Array<String>, name: String): Double? {
    val prefix = "--$name="
    return args.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.toDoubleOrNull()
  }

  /**
   * Parses an optional comma-separated list of Double arguments from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to parse.
   * @return The parsed Double values, or `null` if the argument is not found.
   * @throws NumberFormatException if the argument is present but any comma-separated part isn't a
   *   valid Double.
   */
  fun optionalDoubleList(args: Array<String>, name: String): List<Double>? {
    val prefix = "--$name="
    val raw = args.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix) ?: return null
    return raw.split(",").map { it.trim().toDouble() }
  }

  /**
   * Parses an optional boolean argument from the command-line arguments.
   *
   * @param args Array of command-line arguments.
   * @param name Name of the argument to parse.
   * @param default Default boolean value to return if the argument is not found or cannot be
   *   parsed.
   * @return The parsed boolean value or the default value if parsing fails.
   */
  fun optionalBoolean(args: Array<String>, name: String, default: Boolean): Boolean {
    val prefix = "--$name="
    val raw = args.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix) ?: return default
    return raw.equals("true", ignoreCase = true)
  }
}
