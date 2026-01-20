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

package tools.aqua.stars.data.sumo.xml.importer

import tools.aqua.stars.data.sumo.dataclasses.staticData.Connection

/**
 * Raw connection record that is later resolved to a [Connection] once all lanes are known.
 *
 * @property fromEdgeId Source edge id.
 * @property toEdgeId Target edge id.
 * @property fromLaneIndex Source lane index.
 * @property toLaneIndex Target lane index.
 * @property viaLaneId Via lane id (internal lane).
 * @property directionRaw Raw `dir` string from XML.
 * @property signalStateRaw Raw `state` string from XML.
 */
data class ConnectionRaw(
    val fromEdgeId: String,
    val toEdgeId: String,
    val fromLaneIndex: Int,
    val toLaneIndex: Int,
    val viaLaneId: String,
    val directionRaw: String,
    val signalStateRaw: String
)
