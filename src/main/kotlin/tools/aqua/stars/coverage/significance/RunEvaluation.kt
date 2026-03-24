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

import tools.aqua.stars.core.serialization.tsc.SerializableTSCNode
import tools.aqua.stars.coverage.significance.db.DbBootstrap
import tools.aqua.stars.coverage.significance.db.repositories.EvaluationRunsRepository
import tools.aqua.stars.coverage.significance.db.repositories.TSCsRepository
import tools.aqua.stars.coverage.significance.process.NamedProcess
import tools.aqua.stars.coverage.significance.process.ProcessGroupRunner
import tools.aqua.stars.coverage.significance.utils.getJsonString
import tools.aqua.stars.coverage.significance.workers.startEvaluationWorkerProcess

/**
 * Main entry point for running evaluation workers in parallel.
 *
 * @param args Command line arguments. Supports: --bufferProcessors=<number> : Number of processors
 *   to reserve for buffering (default: 0).
 */
fun main(args: Array<String>) {
  val bufferProcessors = parseIntArg(args, "--bufferProcessors", 0).coerceAtLeast(0)
  val parallelism = (Runtime.getRuntime().availableProcessors() - bufferProcessors).coerceAtLeast(1)
  println("Starting evaluation with parallelism=$parallelism (bufferProcessors=$bufferProcessors).")
  DbBootstrap.connect()
  val evaluationRunId =
      EvaluationRunsRepository.getLatest()?.id
          ?: error("No evaluation run found; cannot start evaluation workers.")

  val tscEntryId =
      TSCsRepository.getByJson(SerializableTSCNode(tsc().rootNode).getJsonString())?.id
          ?: error("Static TSC not found in database; cannot start evaluation workers.")

  val processes: List<NamedProcess> =
      (0 until parallelism).map { idx ->
        NamedProcess(
            name = "worker-$idx",
            process =
                startEvaluationWorkerProcess(
                    workerId = "worker-$idx",
                    evaluationRunId = evaluationRunId,
                    tscEntryId = tscEntryId))
      }
  try {
    ProcessGroupRunner.awaitAll(groupLabel = "evaluation worker", processes = processes)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    processes.toList().forEach { it.killProcessTree() }
    throw e
  }
}

/**
 * Parses the parallelism argument from the command line arguments.
 *
 * @param args Command line arguments.
 * @param longName The long name of the argument to parse (e.g., "--parallelism").
 * @param defaultValue The default value to return if the argument is not provided or invalid.
 * @return The parsed parallelism value, or the default value of 4 if not provided or invalid.
 */
private fun parseIntArg(args: Array<String>, longName: String, defaultValue: Int): Int {
  // Supports: --name=123  and  --name 123
  args
      .firstOrNull { it.startsWith("$longName=") }
      ?.substringAfter("=")
      ?.toIntOrNull()
      ?.let {
        return it
      }

  args
      .indexOfFirst { it == longName }
      .takeIf { it >= 0 && it + 1 < args.size }
      ?.let { idx -> args[idx + 1].toIntOrNull() }
      ?.let {
        return it
      }

  return defaultValue
}
