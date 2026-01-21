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

package tools.aqua.stars.data.sumo.dataclasses.staticData

/**
 * A directed connection between lanes in a SUMO network (`<connection>` in *.net.xml).
 *
 * @property incomingLane The lane the connection starts from (derived from `from` + `fromLane`).
 * @property outgoingLane The lane the connection leads to (derived from `to` + `toLane`).
 * @property viaLane Optional internal lane (attribute `via`). This is `null` when the network does
 *   not model internal lanes for that connection (e.g. built with `--no-internal-links`).
 * @property direction Turning direction (attribute `dir`).
 * @property signalState Right-of-way / traffic-light state (attribute `state`).
 */
data class Connection(
    val incomingLane: Lane,
    val outgoingLane: Lane,
    val viaLane: Lane?,
    val direction: ConnectionDirection,
    val signalState: ConnectionSignalState,
)
