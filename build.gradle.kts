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

val starsVersion = "2.0-coverage-significance-17-5af075b-SNAPSHOT"

dependencies {
  testImplementation(kotlin("test-junit5"))
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
  implementation(group = "tools.aqua", name = "stars-core", version = starsVersion)
  testImplementation(
      group = "tools.aqua",
      name = "stars-core",
      version = starsVersion,
      classifier = "test-fixtures")
  implementation(group = "tools.aqua", name = "stars-logic-kcmftbl", version = starsVersion)
  implementation(
      group = "org.jetbrains.lets-plot", name = "lets-plot-kotlin-jvm", version = "4.9.3")
  detektPlugins(
      group = "io.gitlab.arturbosch.detekt", name = "detekt-rules-libraries", version = "1.23.8")

  implementation("org.jetbrains.exposed:exposed-core:0.53.0")
  implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
  implementation("org.jetbrains.exposed:exposed-java-time:0.53.0")

  runtimeOnly("org.postgresql:postgresql:42.7.3")
  implementation(
      group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.9.0")
  implementation(group = "org.eclipse.sumo", name = "libsumo", version = "1.24.0")
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

      mainClass.set("tools.aqua.stars.coverage.significance.BuildAndStaticallyAnalyzeScenariosKt")
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

kotlin { jvmToolchain(21) }
