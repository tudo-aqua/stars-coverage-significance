/*
 * Copyright 2025-2026 The STARS Coverage Significance Authors
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

import java.net.URI

plugins {
  kotlin("jvm") version "2.2.0"
  application
  id("com.diffplug.spotless") version "7.0.2"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
  kotlin("plugin.serialization") version "2.3.0"
}

repositories {
  mavenCentral()
  maven { url = URI("https://repo.eclipse.org/content/repositories/sumo-releases/") }
  maven { url = URI("https://central.sonatype.com/repository/maven-snapshots/") }
}

dependencies {
  testImplementation(kotlin("test-junit5"))
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
  implementation(group = "tools.aqua", name = "stars-core")
  testImplementation(testFixtures("tools.aqua:stars-core"))
  implementation(group = "tools.aqua", name = "stars-logic-kcmftbl")
  implementation(
      group = "org.jetbrains.lets-plot", name = "lets-plot-kotlin-jvm", version = "4.9.3")
  detektPlugins(
      group = "io.gitlab.arturbosch.detekt", name = "detekt-rules-libraries", version = "1.23.8")

  implementation("org.jetbrains.exposed:exposed-core:0.53.0")
  implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
  implementation("org.jetbrains.exposed:exposed-java-time:0.53.0")

  implementation("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.12.0")
  implementation("org.jetbrains.lets-plot:lets-plot-image-export:4.8.1")

  runtimeOnly("org.postgresql:postgresql:42.7.3")
  implementation(
      group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.9.0")
  implementation(group = "org.eclipse.sumo", name = "libsumo", version = "1.25.0")
  implementation("com.zaxxer:HikariCP:5.1.0")
}

detekt {
  basePath = rootProject.projectDir.absolutePath
  config.setFrom(files(rootProject.file("contrib/detekt-rules.yml")))
}

spotless {
  kotlin {
    licenseHeaderFile(rootProject.file("contrib/license-header.template.kt")).also {
      it.updateYearWithLatest(true)
    }
    ktfmt()
  }
  kotlinGradle {
    licenseHeaderFile(
            rootProject.file("contrib/license-header.template.kt"),
            "(import |@file|plugins |dependencyResolutionManagement|rootProject.name)")
        .also { it.updateYearWithLatest(true) }
    ktfmt()
  }
}

tasks.test { useJUnitPlatform() }

application { mainClass.set("tools.aqua.stars.coverage.significance.RunEvaluationKt") }

val prepareDatabaseAndSeedWithScenariosAndMutants by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Prepares the database and seed the scenarios and mutants."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set(
          "tools.aqua.stars.coverage.significance.PrepareDatabaseAndSeedWithScenariosAndMutantsKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val buildMaterializedViews by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Builds materialized views."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.BuildMaterializedViewsKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val createChunkJobs by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Create chunk jobs."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.CreateChunkJobsKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val runEvaluation by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Runs the evaluation."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunEvaluationKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val filterDuplicateMutants by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Filters duplicate mutants."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.FilterDuplicateMutantsKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val startProgressMonitor by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Start the progress monitor for evaluation."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.StartProgressMonitorKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val runPostEvaluation by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Start the post evaluation process."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.PostEvaluationKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      jvmArgs = listOf("-Xmx300g")
      // args = listOf("--flag", "value")
    }

val runBaselineNextTickDrawScenario by
    tasks.registering(JavaExec::class) {
      group = "application"
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunBaselineNextTickDrawScenarioKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx300g")
    }

val runBaselineNextTickTimeToKillDrawScenario by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "For each accident mutant, measure how many starting scenarios each strategy needs to first kill it (evaluateTimeToKill)."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set(
          "tools.aqua.stars.coverage.significance.RunBaselineNextTickTimeToKillDrawScenarioKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx300g")
    }

val runDrawTicksWithDecisionTreeGrouping by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "Sample individual ticks (uniform-random, DC-leaf round-robin, DC-leaf weighted) and measure distinct mutants killed per suite size, plus time-to-kill per mutant."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set(
          "tools.aqua.stars.coverage.significance.RunDrawTicksWithDecisionTreeGroupingKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx300g")
    }

val runDecisionTreeComparison by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "Compare decision tree runs by exporting per-run metadata and leaf bucket information to JSON."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunDecisionTreeComparisonKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx300g")
    }

val runAnalyzeDuplicateTicks by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "Check metric_failed_monitors for duplicate ticks under decreasing rounding precision."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunAnalyzeDuplicateTicksKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx400g")
    }

val runTickReplay by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "Replay recorded ticks in SUMO and let every mutant control the ego for one step, to compare next-tick behavior."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunTickReplayKt")
      classpath = sourceSets.main.get().runtimeClasspath

      jvmArgs = listOf("-Xmx300g")
    }

val runG0MutantCoverageReplay by
    tasks.registering(JavaExec::class) {
      group = "application"
      description =
          "Coordinator: for every tick whose next tick was recorded as a G0 (Accidents) failure, spawns one worker process per available core to replay it with every known mutant (checking whether the original mutant still fails and whether other mutants additionally fail), then aggregates a summary."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunG0MutantCoverageReplayKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // args = listOf("--runId=8", "--bufferProcessors=2")
    }

val createHighwayTrafficAnalysisChunkJobs by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Create highway traffic chunk jobs."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.CreateHighwayTrafficChunkJobsKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val runHighwayTrafficAnalysis by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Run highway traffic analysis."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.RunHighwayTrafficAnalysisKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

val startHighwayAnalysisProgressMonitor by
    tasks.registering(JavaExec::class) {
      group = "application"
      description = "Start the progress monitor for highway analysis."
      dependsOn(tasks.run.get().taskDependencies)

      mainClass.set("tools.aqua.stars.coverage.significance.StartHighwayAnalysisProgressMonitorKt")
      classpath = sourceSets.main.get().runtimeClasspath

      // optional
      // jvmArgs = listOf("-Xmx64g")
      // args = listOf("--flag", "value")
    }

kotlin { jvmToolchain(21) }
