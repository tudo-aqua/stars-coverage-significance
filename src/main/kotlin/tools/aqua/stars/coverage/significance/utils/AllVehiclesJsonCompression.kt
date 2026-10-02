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

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** First byte of every `all_vehicles_json_deflate` value written by [compressAllVehiclesJson]. */
const val ALL_VEHICLES_JSON_FORMAT_VERSION: Byte = 1

/**
 * Preset DEFLATE dictionary for format version [ALL_VEHICLES_JSON_FORMAT_VERSION]: text that occurs
 * in almost every tick's vehicle JSON (keys, vehicle ids, type ids, and the departure speeds of the
 * background vehicle types). DEFLATE can then encode those parts as back-references into the
 * dictionary instead of spelling them out once per row. Most frequent text comes last, because
 * nearer back-references are cheaper to encode.
 *
 * Must never change for format version 1, or every stored value becomes unreadable — add a new
 * version instead. `scripts/all_vehicles_json_codec.py` and `tools/tick_visualizer/index.html` keep
 * verbatim copies; `AllVehiclesJsonCompressionTest` checks that they match.
 */
const val ALL_VEHICLES_JSON_DICTIONARY: String =
    """"lane":2,"front":"lane":1,"front":"lane":0,"front":""" +
        """{"id":"slow_3","type":"car_calm",{"id":"normal_3","type":"car_normal",""" +
        """{"id":"fast_3","type":"car_speedy",""" +
        """[{"id":"ego","ego":true,"type":"mutant","lane":""" +
        """[{"id":"ego","ego":true,"type":"ego","lane":""" +
        ""","speed":16.666666,"accel":0.0},{"id":"slow_2","type":"car_calm","lane":""" +
        ""","speed":33.333332,"accel":0.0},{"id":"fast_2","type":"car_speedy","lane":""" +
        ""","speed":25.0,"accel":0.0},{"id":"normal_2","type":"car_normal","lane":""" +
        ""","speed":16.666666,"accel":0.0},{"id":"slow_1","type":"car_calm","lane":""" +
        ""","speed":33.333332,"accel":0.0},{"id":"fast_1","type":"car_speedy","lane":""" +
        ""","speed":25.0,"accel":0.0},{"id":"normal_1","type":"car_normal","lane":""" +
        ""","back":,"speed":,"accel":.0,"back":"""

private val dictionaryBytes = ALL_VEHICLES_JSON_DICTIONARY.toByteArray(Charsets.UTF_8)

/**
 * Compresses the vehicle JSON of a `metric_failed_monitors` row (a JSON array of
 * [tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot]) into the
 * bytes stored in the `all_vehicles_json_deflate` column.
 *
 * Format: one version byte ([ALL_VEHICLES_JSON_FORMAT_VERSION]), followed by raw DEFLATE (no
 * zlib/gzip header, which would add 6–18 bytes to every one of the ~10⁹ rows) using the preset
 * dictionary [ALL_VEHICLES_JSON_DICTIONARY]. PostgreSQL's own TOAST compression never kicks in for
 * this table: it only compresses values once a row exceeds ~2 KB, and a row here is ~1 KB, so the
 * JSON (~75% of the row) used to be stored uncompressed. This shrinks it to roughly a fifth.
 *
 * Decode outside the JVM with `scripts/all_vehicles_json_codec.py`.
 *
 * @param json JSON string to compress.
 * @return The bytes to store in `all_vehicles_json_deflate`.
 */
fun compressAllVehiclesJson(json: String): ByteArray {
  val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap= */ true)
  try {
    deflater.setDictionary(dictionaryBytes)
    deflater.setInput(json.toByteArray(Charsets.UTF_8))
    deflater.finish()
    val out = ByteArrayOutputStream(json.length / 3)
    out.write(ALL_VEHICLES_JSON_FORMAT_VERSION.toInt())
    val buffer = ByteArray(1024)
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer))
    }
    return out.toByteArray()
  } finally {
    deflater.end()
  }
}

/**
 * Inverse of [compressAllVehiclesJson].
 *
 * @param bytes Value of the `all_vehicles_json_deflate` column.
 * @return The original JSON string.
 */
fun decompressAllVehiclesJson(bytes: ByteArray): String {
  require(bytes.isNotEmpty() && bytes[0] == ALL_VEHICLES_JSON_FORMAT_VERSION) {
    "Unknown all_vehicles_json_deflate format version ${bytes.firstOrNull()}"
  }
  val inflater = Inflater(/* nowrap= */ true)
  try {
    inflater.setDictionary(dictionaryBytes)
    inflater.setInput(bytes, 1, bytes.size - 1)
    val out = ByteArrayOutputStream(bytes.size * 6)
    val buffer = ByteArray(4096)
    while (!inflater.finished()) {
      val n = inflater.inflate(buffer)
      check(n > 0 || !inflater.needsInput()) { "Truncated all_vehicles_json_deflate value" }
      out.write(buffer, 0, n)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
  } finally {
    inflater.end()
  }
}
