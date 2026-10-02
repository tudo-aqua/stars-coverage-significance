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

package tools.aqua.stars.coverage.significance.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table

/**
 * PostgreSQL `BYTEA` column whose values are sent to the database as hex text (`\x0a1b…`) instead
 * of as a binary parameter.
 *
 * The connection uses `preferQueryMode=simple` (required behind PgBouncer, see [DbBootstrap]), in
 * which the PostgreSQL JDBC driver inlines every parameter into the SQL text — except binary ones:
 * Exposed's own `binary()` column binds via `setBytes`, which the driver then leaves as a bare `?`,
 * failing with `syntax error at or near ","`. A hex string is inlined like any other text and
 * PostgreSQL converts it to `BYTEA` on assignment. Reading is unaffected: the driver decodes
 * `BYTEA` results to `ByteArray` in either mode.
 */
class HexByteaColumnType : ColumnType<ByteArray>() {
  override fun sqlType(): String = "BYTEA"

  override fun valueFromDB(value: Any): ByteArray =
      when (value) {
        is ByteArray -> value
        is String ->
            value.removePrefix("\\x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else -> error("Unexpected value of type ${value::class.qualifiedName} for a BYTEA column")
      }

  override fun notNullValueToDB(value: ByteArray): Any = "\\x" + value.toHex()

  override fun nonNullValueToString(value: ByteArray): String = "'\\x${value.toHex()}'::bytea"

  private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
      val v = this[i].toInt() and 0xff
      chars[2 * i] = HEX_DIGITS[v ushr 4]
      chars[2 * i + 1] = HEX_DIGITS[v and 0x0f]
    }
    return String(chars)
  }

  private companion object {
    val HEX_DIGITS = "0123456789abcdef".toCharArray()
  }
}

/** Declares a [HexByteaColumnType] column. */
fun Table.hexBytea(name: String): Column<ByteArray> = registerColumn(name, HexByteaColumnType())
