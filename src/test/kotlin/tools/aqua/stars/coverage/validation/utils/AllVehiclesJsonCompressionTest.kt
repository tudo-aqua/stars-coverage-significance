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

package tools.aqua.stars.coverage.validation.utils

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.aqua.stars.coverage.significance.utils.ALL_VEHICLES_JSON_DICTIONARY
import tools.aqua.stars.coverage.significance.utils.compressAllVehiclesJson
import tools.aqua.stars.coverage.significance.utils.decompressAllVehiclesJson

/** Tests for [compressAllVehiclesJson] and [decompressAllVehiclesJson]. */
class AllVehiclesJsonCompressionTest {

  private val tickJson =
      """[{"id":"ego","ego":true,"type":"ego","lane":0,"front":133.43282,"back":128.43282,"speed":27.048191,"accel":2.2819085},{"id":"fast_1","type":"car_speedy","lane":2,"front":140.0,"back":135.0,"speed":33.333332,"accel":0.0},{"id":"fast_2","type":"car_speedy","lane":2,"front":200.0,"back":195.0,"speed":33.333332,"accel":0.0},{"id":"normal_1","type":"car_normal","lane":1,"front":70.86728,"back":65.86728,"speed":22.43478,"accel":-0.98870057},{"id":"normal_2","type":"car_normal","lane":0,"front":192.5,"back":187.5,"speed":25.0,"accel":0.0},{"id":"normal_3","type":"car_normal","lane":1,"front":192.5,"back":187.5,"speed":25.0,"accel":0.0},{"id":"slow_1","type":"car_calm","lane":0,"front":65.0,"back":60.0,"speed":16.666666,"accel":0.0},{"id":"slow_2","type":"car_calm","lane":1,"front":125.0,"back":120.0,"speed":16.666666,"accel":0.0}]"""

  /** A compressed tick decompresses back to the identical JSON string and is much smaller. */
  @Test
  fun `Test round trip of a recorded tick`() {
    val compressed = compressAllVehiclesJson(tickJson)

    assertEquals(tickJson, decompressAllVehiclesJson(compressed))
    assertTrue(compressed.size < tickJson.length / 4, "compressed to ${compressed.size} bytes")
  }

  /**
   * A value produced by `scripts/all_vehicles_json_codec.py` decodes correctly, i.e. the Python
   * codec writes exactly the format the Kotlin side reads.
   */
  @Test
  fun `Test decoding a value produced by the Python codec`() {
    val pythonBytes =
        Base64.getDecoder()
            .decode(
                "Aa2UQRIDIQgEXxQKRhF8TP7/jZhUId5Wd3PEg80wAycJyDzLkFgLHPGNwOMhWjfi6tJlQkEYNbuejCK3TCpn21J+k/lXkpICXil9n7LlSJ4IY/Jm8EA1jTrmhzHQaj5hL6bubsxqZ/lebOsgneLcvsVm0sqmrjuIy81KBU3TncaLOQ+P0iIAC0JwzXh/AA==")

    assertEquals(tickJson, decompressAllVehiclesJson(pythonBytes))
  }

  /**
   * The Python codec and the tick visualizer keep verbatim copies of the preset dictionary; if one
   * of them drifts from [ALL_VEHICLES_JSON_DICTIONARY], they can no longer decode stored values.
   */
  @Test
  fun `Test dictionary copies match the Kotlin dictionary`() {
    for (copy in listOf("scripts/all_vehicles_json_codec.py", "tools/tick_visualizer/index.html")) {
      assertTrue(
          File(copy).readText().contains("'$ALL_VEHICLES_JSON_DICTIONARY'"),
          "$copy does not contain the current ALL_VEHICLES_JSON_DICTIONARY")
    }
  }
}
