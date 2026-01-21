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

val starsVersion = "2.0-coverage-significance-15-59d7704-SNAPSHOT"

dependencies {
  testImplementation(kotlin("test-junit5"))
  testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.2")
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
  implementation(group = "org.eclipse.sumo", name = "libsumo", version = "1.25.0")
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

application { mainClass.set("tools.aqua.stars.coverage.significance.MainKt") }

kotlin { jvmToolchain(21) }
