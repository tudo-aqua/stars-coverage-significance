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

/**
 * Compresses the vehicle JSON of a `metric_failed_monitors` row (a JSON array of
 * [tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TickVehicleSnapshot]) into the
 * bytes stored in the `all_vehicles_json_deflate` column.
 *
 * Uses raw DEFLATE (no zlib/gzip header, which would add 6–18 bytes to every one of the ~10⁹ rows).
 * PostgreSQL's own TOAST compression never kicks in for this table: it only compresses values once
 * a row exceeds ~2 KB, and a row here is ~1 KB, so the JSON (~75% of the row) used to be stored
 * uncompressed. Raw DEFLATE shrinks it to roughly a third.
 *
 * Decode outside the JVM with Python's standard library: `zlib.decompress(data, -15).decode()`.
 *
 * @param json JSON string to compress.
 * @return Raw-DEFLATE-compressed UTF-8 bytes of [json].
 */
fun compressAllVehiclesJson(json: String): ByteArray {
  val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap= */ true)
  try {
    deflater.setInput(json.toByteArray(Charsets.UTF_8))
    deflater.finish()
    val out = ByteArrayOutputStream(json.length / 2)
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
 * @param bytes Raw-DEFLATE-compressed bytes as stored in `all_vehicles_json_deflate`.
 * @return The original JSON string.
 */
fun decompressAllVehiclesJson(bytes: ByteArray): String {
  val inflater = Inflater(/* nowrap= */ true)
  try {
    inflater.setInput(bytes)
    val out = ByteArrayOutputStream(bytes.size * 4)
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
