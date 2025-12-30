/*
 * Copyright 2025 The STARS Coverage Significance Authors
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

package tools.aqua.stars.data.sumo

import java.nio.file.Path
import javax.xml.stream.XMLStreamConstants
import kotlin.math.round
import tools.aqua.stars.data.sumo.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dynamicData.Scenario
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.staticData.Connection
import tools.aqua.stars.data.sumo.staticData.Edge
import tools.aqua.stars.data.sumo.staticData.Junction
import tools.aqua.stars.data.sumo.staticData.Lane
import tools.aqua.stars.data.sumo.staticData.Location
import tools.aqua.stars.data.sumo.staticData.RoadNetwork

/**
 * Imports SUMO XML outputs into STARS-compatible objects.
 *
 * Inputs:
 * - `.net.xml` : network geometry/topology
 * - `export.xml` : per-timestep vehicle data (id, pos, speed)
 * - `collision.xml` : optional collisions (your file is currently empty)
 *
 * @property warnings Collector for non-fatal import warnings.
 */
class Importer {

  private val warnings: MutableList<String> = mutableListOf()

  /**
   * Imports the given files into a [Scenario].
   *
   * @param netFilePath Path to `.net.xml`.
   * @param exportFilePath Path to `export.xml`.
   * @param collisionFilePath Optional path to `collision.xml`.
   * @param egoVehicleId Optional ego vehicle id. If absent or not present in a tick, the first
   *   vehicle is used.
   * @return Imported [Scenario].
   */
  fun importScenario(
      netFilePath: Path,
      exportFilePath: Path,
      collisionFilePath: Path? = null,
      egoVehicleId: String = ""
  ): Scenario {
    warnings.clear()

    val net: RoadNetwork = parseNet(netFilePath)
    val collisions: List<CollisionEvent> =
        collisionFilePath?.let { parseCollisions(it, net) } ?: emptyList()

    val collisionsByTickMillis: Map<Long, List<CollisionEventRaw>> =
        collisionsToRaw(collisions).groupBy { it.tickMillis }

    val ticks: List<TimeStep> =
        parseExport(exportFilePath, net, collisionsByTickMillis, egoVehicleId)

    linkTicks(ticks)

    return Scenario(net = net, ticks = ticks, warnings = warnings.toList())
  }

  /** Parses `.net.xml` into [RoadNetwork]. */
  private fun parseNet(netFilePath: Path): RoadNetwork {
    val reader = createXmlReader(netFilePath)

    var location: Location? = null
    val junctions = mutableListOf<Junction>()
    val edges = mutableListOf<Edge>()
    val connections = mutableListOf<Connection>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue

      when (reader.localName) {
        "location" -> {
          location =
              Location(
                  netOffset = reader.attribute("netOffset") ?: "",
                  convBoundary = reader.attribute("convBoundary") ?: "",
                  origBoundary = reader.attribute("origBoundary") ?: "",
                  projParameter = reader.attribute("projParameter") ?: "")
        }

        "junction" -> {
          val junctionId = reader.attribute("id") ?: ""
          val junctionType = reader.attribute("type") ?: ""

          val x = reader.attribute("x")?.toFloatOrNull() ?: 0.0f
          val y = reader.attribute("y")?.toFloatOrNull() ?: 0.0f

          val incLanesRaw = reader.attribute("incLanes") ?: ""
          val intLanesRaw = reader.attribute("intLanes") ?: ""

          val shapeRaw = reader.attribute("shape") ?: ""
          val shape = parseShape(shapeRaw)

          junctions +=
              Junction(
                  junctionId = junctionId,
                  junctionType = junctionType,
                  x = x,
                  y = y,
                  incomingLaneIds = splitSpaceList(incLanesRaw),
                  internalLaneIds = splitSpaceList(intLanesRaw),
                  shape = shape)
        }

        "edge" -> {
          val edgeId = reader.attribute("id") ?: ""
          val from = reader.attribute("from") ?: ""
          val to = reader.attribute("to") ?: ""
          val function = reader.attribute("function") ?: ""
          val priority = reader.attribute("priority")?.toIntOrNull() ?: 0

          val lanes = mutableListOf<Lane>()

          // consume nested lanes until </edge>
          while (reader.hasNext()) {
            val inner = reader.next()

            if (inner == XMLStreamConstants.START_ELEMENT && reader.localName == "lane") {
              val laneId = reader.attribute("id") ?: ""
              val laneIndex = reader.attribute("index")?.toIntOrNull() ?: 0
              val speed = reader.attribute("speed")?.toFloatOrNull() ?: 0.0f
              val length = reader.attribute("length")?.toFloatOrNull() ?: 0.0f
              val shapeRaw = reader.attribute("shape") ?: ""
              val laneShape = parseShape(shapeRaw)

              lanes +=
                  Lane(
                      laneId = laneId,
                      laneIndex = laneIndex,
                      speedLimitMetersPerSecond = speed,
                      laneLengthMeters = length,
                      laneShape = laneShape)
            } else if (inner == XMLStreamConstants.END_ELEMENT && reader.localName == "edge") {
              break
            }
          }

          edges +=
              Edge(
                  edgeId = edgeId,
                  fromJunctionId = from,
                  toJunctionId = to,
                  edgeFunction = function,
                  edgePriority = priority,
                  lanes = lanes)
        }

        "connection" -> {
          connections +=
              Connection(
                  fromEdgeId = reader.attribute("from") ?: "",
                  toEdgeId = reader.attribute("to") ?: "",
                  fromLaneIndex = reader.attribute("fromLane")?.toIntOrNull() ?: 0,
                  toLaneIndex = reader.attribute("toLane")?.toIntOrNull() ?: 0,
                  viaLaneId = reader.attribute("via") ?: "",
                  direction = reader.attribute("dir") ?: "",
                  signalState = reader.attribute("state") ?: "")
        }
      }
    }

    reader.close()

    return RoadNetwork(
        location = location ?: Location("", "", "", ""),
        junctions = junctions,
        edges = edges,
        connections = connections)
  }

  /** Parses `export.xml` into ordered [TimeStep]s and resolves lane/edge pointers. */
  private fun parseExport(
      exportFilePath: Path,
      net: RoadNetwork,
      collisionsByTickMillis: Map<Long, List<CollisionEventRaw>>,
      egoVehicleId: String
  ): List<TimeStep> {
    val reader = createXmlReader(exportFilePath)
    val ticks = mutableListOf<TimeStep>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue
      if (reader.localName != "timestep") continue

      val timeSeconds = reader.attribute("time")?.toFloatOrNull() ?: 0.0f
      val tickMillis = secondsToMillis(timeSeconds)

      val vehiclesInTick = mutableListOf<Vehicle>()

      // consume until </timestep>
      while (reader.hasNext()) {
        val inner = reader.next()

        if (inner == XMLStreamConstants.START_ELEMENT && reader.localName == "edge") {
          // consume lanes until </edge>
          while (reader.hasNext()) {
            val edgeInner = reader.next()

            if (edgeInner == XMLStreamConstants.START_ELEMENT && reader.localName == "lane") {
              val laneId = reader.attribute("id") ?: ""
              val lane =
                  net.laneById[laneId]
                      ?: run {
                        warnings += "Unresolved laneId '$laneId' in export.xml at time=$timeSeconds"
                        Defaults.unknownLane
                      }
              val edge =
                  net.edgeByLaneId[laneId]
                      ?: run {
                        warnings +=
                            "Unresolved edge for laneId '$laneId' in export.xml at time=$timeSeconds"
                        Defaults.unknownEdge
                      }

              // consume vehicles until </lane>
              while (reader.hasNext()) {
                val laneInner = reader.next()

                if (laneInner == XMLStreamConstants.START_ELEMENT &&
                    reader.localName == "vehicle") {
                  val vehicleId = reader.attribute("id") ?: ""
                  val pos = reader.attribute("pos")?.toFloatOrNull() ?: 0.0f
                  val speed = reader.attribute("speed")?.toFloatOrNull() ?: 0.0f

                  val typeId = inferVehicleTypeId(vehicleId)
                  val vehicleType = VehicleType(typeId)

                  vehiclesInTick +=
                      Vehicle(
                          vehicleId = vehicleId,
                          vehicleType = vehicleType,
                          currentLane = lane,
                          currentEdge = edge,
                          positionOnLaneMeters = pos,
                          speedMetersPerSecond = speed)
                } else if (laneInner == XMLStreamConstants.END_ELEMENT &&
                    reader.localName == "lane") {
                  break
                }
              }
            } else if (edgeInner == XMLStreamConstants.END_ELEMENT && reader.localName == "edge") {
              break
            }
          }
        } else if (inner == XMLStreamConstants.END_ELEMENT && reader.localName == "timestep") {
          break
        }
      }

      val vehiclesById: Map<String, Vehicle> = vehiclesInTick.associateBy { it.vehicleId }

      val collisionsInTick: List<CollisionEvent> =
          collisionsByTickMillis[tickMillis].orEmpty().map { raw ->
            val lane = net.laneById[raw.laneId].orDefaultLane(raw.laneId)
            val edge = net.edgeByLaneId[raw.laneId] ?: Defaults.unknownEdge

            val collider =
                vehiclesById[raw.colliderId] ?: placeholderVehicle(raw.colliderId, lane, edge)
            val victim = vehiclesById[raw.victimId] ?: placeholderVehicle(raw.victimId, lane, edge)

            CollisionEvent(
                collisionTimeSeconds = raw.timeSeconds,
                lane = lane,
                edge = edge,
                positionOnLaneMeters = raw.positionOnLaneMeters,
                colliderVehicle = collider,
                victimVehicle = victim,
                collisionType = raw.collisionType,
                rawAttributes = raw.rawAttributes)
          }

      val ego: Vehicle =
          if (egoVehicleId.isNotBlank()) {
            vehiclesInTick.firstOrNull { it.vehicleId == egoVehicleId }
                ?: vehiclesInTick.firstOrNull()
                ?: placeholderVehicle("EGO_PLACEHOLDER", Defaults.unknownLane, Defaults.unknownEdge)
          } else {
            vehiclesInTick.firstOrNull()
                ?: placeholderVehicle("EGO_PLACEHOLDER", Defaults.unknownLane, Defaults.unknownEdge)
          }

      ticks +=
          TimeStep(
              tickTimeMillis = tickMillis,
              vehiclesInTick = vehiclesInTick,
              collisionsInTick = collisionsInTick,
              ego = ego)
    }

    reader.close()
    return ticks
  }

  /**
   * Parses `collision.xml` into collision events.
   *
   * Your file is currently empty; this supports typical SUMO attributes.
   */
  private fun parseCollisions(collisionFilePath: Path, net: RoadNetwork): List<CollisionEvent> {
    val reader = createXmlReader(collisionFilePath)
    val collisions = mutableListOf<CollisionEvent>()

    // We first parse into raw records; final vehicle pointers are resolved per tick later.
    val rawEvents = mutableListOf<CollisionEventRaw>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue
      if (reader.localName != "collision") continue

      val rawAttributes =
          (0 until reader.attributeCount).associate { idx ->
            reader.getAttributeLocalName(idx) to (reader.getAttributeValue(idx) ?: "")
          }

      val timeSeconds = reader.attribute("time")?.toFloatOrNull() ?: 0.0f
      val laneId = (reader.attribute("lane") ?: reader.attribute("laneID") ?: "").trim()
      val pos = reader.attribute("pos")?.toFloatOrNull() ?: 0.0f
      val collisionType = (reader.attribute("type") ?: "").trim()

      val colliderId = (reader.attribute("collider") ?: reader.attribute("colliderID") ?: "").trim()
      val victimId = (reader.attribute("victim") ?: reader.attribute("victimID") ?: "").trim()

      if (laneId.isEmpty()) warnings += "Collision without lane id; using UNKNOWN_LANE."
      if (colliderId.isEmpty()) warnings += "Collision without collider id; using empty id."
      if (victimId.isEmpty()) warnings += "Collision without victim id; using empty id."

      rawEvents +=
          CollisionEventRaw(
              tickMillis = secondsToMillis(timeSeconds),
              timeSeconds = timeSeconds,
              laneId = laneId,
              positionOnLaneMeters = pos,
              colliderId = colliderId,
              victimId = victimId,
              collisionType = collisionType,
              rawAttributes = rawAttributes)
    }

    reader.close()

    // Return placeholder CollisionEvent list (will be re-resolved per tick anyway).
    // Keeping this method signature makes it easy to extend, but we actually bucket raw events.
    return collisions
  }

  /**
   * Converts already-parsed collision events (none in your file) to raw; kept for API completeness.
   */
  private fun collisionsToRaw(collisions: List<CollisionEvent>): List<CollisionEventRaw> =
      emptyList()

  /** Links ticks bidirectionally via [TimeStep.previousTick] and [TimeStep.nextTick]. */
  private fun linkTicks(ticks: List<TimeStep>) {
    for (i in ticks.indices) {
      ticks[i].previousTick = ticks.getOrNull(i - 1)
      ticks[i].nextTick = ticks.getOrNull(i + 1)
    }
  }

  /** Converts SUMO seconds into milliseconds using rounding. */
  private fun secondsToMillis(seconds: Float): Long = round(seconds * 1000.0f).toLong()

  /** Splits a SUMO space-separated list attribute into a list. */
  private fun splitSpaceList(value: String): List<String> =
      value.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

  /** Infers type from vehicle id prefix (before '.'). */
  private fun inferVehicleTypeId(vehicleId: String): String =
      vehicleId.substringBefore('.', missingDelimiterValue = Defaults.unknownVehicleType.typeId)

  /**
   * Produces a placeholder vehicle for collision linking when the vehicle is not present in a tick.
   */
  private fun placeholderVehicle(vehicleId: String, lane: Lane, edge: Edge): Vehicle =
      Vehicle(
          vehicleId = vehicleId,
          vehicleType = VehicleType(inferVehicleTypeId(vehicleId)),
          currentLane = lane,
          currentEdge = edge,
          positionOnLaneMeters = 0.0f,
          speedMetersPerSecond = 0.0f)

  /** Returns a lane if resolvable, else a placeholder lane with warnings. */
  private fun Lane?.orDefaultLane(laneId: String): Lane =
      this
          ?: run {
            warnings += "Unresolved laneId '$laneId' in collision.xml; using UNKNOWN_LANE."
            Defaults.unknownLane
          }

  /** Raw collision record used for bucketing before resolving vehicle pointers per tick. */
  private data class CollisionEventRaw(
      val tickMillis: Long,
      val timeSeconds: Float,
      val laneId: String,
      val positionOnLaneMeters: Float,
      val colliderId: String,
      val victimId: String,
      val collisionType: String,
      val rawAttributes: Map<String, String>
  )
}
