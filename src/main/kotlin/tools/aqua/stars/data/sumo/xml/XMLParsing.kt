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

package tools.aqua.stars.data.sumo.xml

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader
import tools.aqua.stars.data.sumo.dataclasses.staticData.Point

/**
 * Creates a streaming XML reader for [xmlFilePath].
 *
 * @param xmlFilePath Path to an XML file.
 * @return XML stream reader.
 */
internal fun createXmlReader(xmlFilePath: Path): XMLStreamReader {
  val factory = XMLInputFactory.newInstance()
  factory.setProperty(XMLInputFactory.IS_COALESCING, true)
  val inputStream: InputStream = Files.newInputStream(xmlFilePath)
  return factory.createXMLStreamReader(inputStream, StandardCharsets.UTF_8.name())
}

/**
 * Reads an attribute by local name.
 *
 * @param attributeName Local attribute name.
 * @return Value or null if missing.
 */
internal fun XMLStreamReader.attribute(attributeName: String): String? =
    (0 until attributeCount)
        .firstOrNull { getAttributeLocalName(it) == attributeName }
        ?.let { getAttributeValue(it) }

/**
 * Parses a SUMO shape string `"x,y x,y ..."` into [Point]s.
 *
 * @param shapeString Raw shape string.
 * @return Parsed polyline.
 */
internal fun parseShape(shapeString: String): List<Point> {
  if (shapeString.isBlank()) return emptyList()
  return shapeString.trim().split(Regex("\\s+")).mapNotNull { token ->
    val parts = token.split(",")
    if (parts.size != 2) return@mapNotNull null
    val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
    val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
    Point(x, y)
  }
}
