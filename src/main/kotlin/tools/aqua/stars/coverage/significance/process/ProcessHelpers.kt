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

package tools.aqua.stars.coverage.significance.process

import java.nio.file.Paths
import kotlin.system.exitProcess

/** Helper functions for starting processes. */
object ProcessHelpers {

  /**
   * Starts a Java process with the specified main class and arguments.
   *
   * @param mainClass The fully qualified name of the main class to run.
   * @param args The arguments to pass to the main class.
   * @return The started process.
   */
  fun startJavaProcess(mainClass: String, args: List<String>): Process {
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
    val classpath = System.getProperty("java.class.path")

    val sumoHome = System.getenv("SUMO_HOME") ?: ""
    val javaLibraryPathArg =
        if (sumoHome.isNotBlank()) "-Djava.library.path=$sumoHome/bin" else null

    val cmd = buildList {
      add(javaBin)
      if (javaLibraryPathArg != null) add(javaLibraryPathArg)
      add("-Xmx6g")
      add("-cp")
      add(classpath)
      add(mainClass)
      addAll(args)
    }

    println("Starting $mainClass ${args.joinToString(" ")}")
    return ProcessBuilder(cmd).inheritIO().start()
  }

  /**
   * Installs a watcher that exits the current process if the parent process dies.
   *
   * @param args The command line arguments containing the parent PID.
   */
  fun installParentDeathWatcher(args: Array<String>) {
    val parentPid =
        args.firstOrNull { it.startsWith("--parentPid=") }?.substringAfter("=")?.toLongOrNull()
            ?: return

    val parentHandle = ProcessHandle.of(parentPid).orElse(null) ?: run { exitProcess(2) }

    Thread(
            {
              while (true) {
                if (!parentHandle.isAlive) exitProcess(130)
                Thread.sleep(500)
              }
            },
            "parent-death-watcher")
        .apply {
          isDaemon = true
          start()
        }
  }
}
