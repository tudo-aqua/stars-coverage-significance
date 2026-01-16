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

package tools.aqua.stars.data.sumo

import java.io.File
import java.nio.file.Path
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.math.round
import tools.aqua.stars.core.evaluation.TickSequence
import tools.aqua.stars.core.evaluation.TickSequence.Companion.asTickSequence
import tools.aqua.stars.coverage.significance.COLLISION_DIR
import tools.aqua.stars.coverage.significance.COLLISION_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.EXPORT_DIR
import tools.aqua.stars.coverage.significance.EXPORT_FILE_EXTENSION
import tools.aqua.stars.coverage.significance.SCENARIO_DIR
import tools.aqua.stars.coverage.significance.SCENARIO_FILE_EXTENSION
import tools.aqua.stars.data.sumo.dynamicData.CollisionEvent
import tools.aqua.stars.data.sumo.dynamicData.Scenario
import tools.aqua.stars.data.sumo.dynamicData.TimeStep
import tools.aqua.stars.data.sumo.dynamicData.Vehicle
import tools.aqua.stars.data.sumo.dynamicData.VehicleType
import tools.aqua.stars.data.sumo.importer.CollisionEventRaw
import tools.aqua.stars.data.sumo.importer.ConnectionRaw
import tools.aqua.stars.data.sumo.importer.EdgeRaw
import tools.aqua.stars.data.sumo.importer.JunctionRaw
import tools.aqua.stars.data.sumo.importer.LaneRaw
import tools.aqua.stars.data.sumo.importer.VehicleTypesFile
import tools.aqua.stars.data.sumo.routeData.FlowDefinition
import tools.aqua.stars.data.sumo.routeData.RouteDefinition
import tools.aqua.stars.data.sumo.routeData.RoutesFile
import tools.aqua.stars.data.sumo.routeData.StopDefinition
import tools.aqua.stars.data.sumo.routeData.TypeParameter
import tools.aqua.stars.data.sumo.routeData.VehicleDefinition
import tools.aqua.stars.data.sumo.routeData.VehicleRouteSpecification
import tools.aqua.stars.data.sumo.routeData.VehicleTypeDefinition
import tools.aqua.stars.data.sumo.staticData.BoundaryBox
import tools.aqua.stars.data.sumo.staticData.Connection
import tools.aqua.stars.data.sumo.staticData.ConnectionDirection
import tools.aqua.stars.data.sumo.staticData.ConnectionSignalState
import tools.aqua.stars.data.sumo.staticData.Edge
import tools.aqua.stars.data.sumo.staticData.Junction
import tools.aqua.stars.data.sumo.staticData.JunctionType
import tools.aqua.stars.data.sumo.staticData.Lane
import tools.aqua.stars.data.sumo.staticData.Location
import tools.aqua.stars.data.sumo.staticData.Point
import tools.aqua.stars.data.sumo.staticData.ProjToken
import tools.aqua.stars.data.sumo.staticData.ProjValue
import tools.aqua.stars.data.sumo.staticData.Projection
import tools.aqua.stars.data.sumo.staticData.RoadNetwork

/** Importer for SUMO simulation data files. */
class SumoImporter {

  /** Collector for non-fatal import warnings. */
  private val warnings: MutableList<String> = mutableListOf()

  /**
   * Loads multiple simulation runs from the given files as a sequence of [TickSequence] objects.
   *
   * @param scenarioFiles List of scenario files.
   * @param exportFiles List of export files.
   * @param collisionsFiles List of collision files.
   * @param bufferSize Buffer size for each [TickSequence].
   * @param netFilePath Path to the `.net.xml` file.
   * @param vehicleTypesAdditionalFilePath Path to the vehicle types additional file.
   * @param takeOnlyTicksAtXMillis Optional filter to only take ticks at every X seconds.
   * @return Sequence of [TickSequence]s for each scenario.
   * @throws IllegalArgumentException if input file lists are empty or mismatched.
   */
  fun loadTicks(
      scenarioFiles: List<File>,
      exportFiles: List<File>,
      collisionsFiles: List<File>,
      bufferSize: Int = 100,
      netFilePath: Path,
      vehicleTypesAdditionalFilePath: Path,
      takeOnlyTicksAtXMillis: Int? = null,
  ): Sequence<TickSequence<TimeStep>> {
    check(scenarioFiles.isNotEmpty()) {
      "The list of scenario files is empty. Cannot load ticks without scenario data."
    }
    check(exportFiles.isNotEmpty()) {
      "The list of export files is empty. Cannot load ticks without simulation runs."
    }
    check(collisionsFiles.isNotEmpty()) {
      "The list of collision files is empty. Cannot load ticks without collision data."
    }
    check(scenarioFiles.size == exportFiles.size && exportFiles.size == collisionsFiles.size) {
      "The number of scenario files, export files, and collision files must be the same."
    }
    val scenarioNames = scenarioFiles.map { it.name.removeSuffix(".$SCENARIO_FILE_EXTENSION") }
    val exportNames = exportFiles.map { it.name.removeSuffix(".$EXPORT_FILE_EXTENSION") }
    val collisionNames = collisionsFiles.map { it.name.removeSuffix(".$COLLISION_FILE_EXTENSION") }

    for (name in scenarioNames) {
      check(name in exportNames) { "No matching export file found for scenario file: $name" }
      check(name in collisionNames) { "No matching collision file found for scenario file: $name" }
    }

    val scenarioNameIterator = scenarioNames.iterator()

    return generateSequence {
      if (!scenarioNameIterator.hasNext()) return@generateSequence null
      val currentScenarioName = scenarioNameIterator.next()
      println("Reading simulation run file: $currentScenarioName")
      val scenarioFilePath = Path.of("$SCENARIO_DIR/${currentScenarioName}.rou.xml")
      val exportFilePath = Path.of("$EXPORT_DIR/${currentScenarioName}.$EXPORT_FILE_EXTENSION")
      val collisionFilePath =
          Path.of("$COLLISION_DIR/${currentScenarioName}.$COLLISION_FILE_EXTENSION")

      // Holds the current scenario object
      val scenario =
          importScenario(
              netFilePath = netFilePath,
              vTypesFilePath = vehicleTypesAdditionalFilePath,
              exportFilePath = exportFilePath,
              routesFilePath = scenarioFilePath,
              collisionFilePath = collisionFilePath)

      // Calculate TickData objects from XMl
      var ticks = scenario.ticks

      // Filter ticks by specified take value if provided
      if (takeOnlyTicksAtXMillis != null) {
        ticks = ticks.filter { tick -> tick.tickTimeMillis % takeOnlyTicksAtXMillis == 0L }
      }
      return@generateSequence ticks.asTickSequence(bufferSize = bufferSize)
    }
  }

  /**
   * Loads a single simulation run from the given files as a list of [TimeStep] objects.
   *
   * @param scenarioFile Scenario file.
   * @param exportFile Export file.
   * @param collisionFile Collision file.
   * @param netFilePath Path to the `.net.xml` file.
   * @param vehicleTypesAdditionalFilePath Path to the vehicle types additional file.
   * @param takeOnlyTicksAtXSeconds Optional filter to only take ticks at every X seconds.
   * @return List of [TimeStep]s for the scenario.
   */
  fun loadTicks(
      scenarioFile: File,
      exportFile: File,
      collisionFile: File,
      netFilePath: Path,
      vehicleTypesAdditionalFilePath: Path,
      takeOnlyTicksAtXSeconds: Double? = null
  ): List<TimeStep> {
    val scenarioName = scenarioFile.name.removeSuffix(".$SCENARIO_FILE_EXTENSION")
    val exportName = exportFile.name.removeSuffix(".$EXPORT_FILE_EXTENSION")
    val collisionName = collisionFile.name.removeSuffix(".$COLLISION_FILE_EXTENSION")

    check(scenarioName == exportName) {
      "No matching export file found for scenario file: $scenarioName"
    }
    check(scenarioName == collisionName) {
      "No matching collision file found for scenario file: $scenarioName"
    }

    println("Reading simulation run file: $scenarioName")
    val scenarioFilePath = Path.of("$SCENARIO_DIR/${scenarioName}.rou.xml")
    val exportFilePath = Path.of("$EXPORT_DIR/${scenarioName}.$EXPORT_FILE_EXTENSION")
    val collisionFilePath = Path.of("$COLLISION_DIR/${scenarioName}.$COLLISION_FILE_EXTENSION")

    // Holds the current scenario object
    val scenario =
        importScenario(
            netFilePath = netFilePath,
            vTypesFilePath = vehicleTypesAdditionalFilePath,
            exportFilePath = exportFilePath,
            routesFilePath = scenarioFilePath,
            collisionFilePath = collisionFilePath)

    // Calculate TickData objects from XMl
    var ticks = scenario.ticks

    // Filter ticks by specified take value if provided
    if (takeOnlyTicksAtXSeconds != null) {
      ticks =
          ticks.filter { tick ->
            val tickTimeSeconds = tick.tickTimeMillis / 1000.0
            tickTimeSeconds % takeOnlyTicksAtXSeconds == 0.0
          }
    }
    return ticks
  }

  /**
   * Imports the given files into a [Scenario].
   *
   * @param netFilePath Path to `.net.xml`.
   * @param exportFilePath Path to `export.xml`.
   * @param routesFilePath Path to `routes.xml`.
   * @param vTypesFilePath Path to `vTypes.add.xml`.
   * @param collisionFilePath Optional path to `collision.xml`.
   * @return Imported [Scenario].
   */
  fun importScenario(
      netFilePath: Path,
      exportFilePath: Path,
      routesFilePath: Path,
      vTypesFilePath: Path,
      collisionFilePath: Path? = null,
  ): Scenario {
    warnings.clear()

    val routesFile = parseRoutesFile(routesFilePath)
    val net: RoadNetwork = parseNet(netFilePath)
    val vTypesFile = parseVehicleTypesAddFile(vTypesFilePath)

    val collisionEvents: List<CollisionEventRaw> =
        collisionFilePath?.let { parseCollisionEvents(it) } ?: emptyList()

    val collisionsByTickMillis: Map<Long, List<CollisionEventRaw>> =
        collisionEvents.groupBy { it.tickMillis }

    val ticks: List<TimeStep> =
        parseExport(exportFilePath, net, routesFile, vTypesFile, collisionsByTickMillis)

    return Scenario(net = net, routes = routesFile, ticks = ticks, warnings = warnings.toList())
  }

  /**
   * Parses a SUMO routes file (`*.rou.xml`) into a [RoutesFile].
   *
   * Note: some example files contain literal `...` lines; these are removed before XML parsing.
   *
   * @param routesFilePath Path to the routes file.
   * @return Parsed [RoutesFile].
   */
  private fun parseRoutesFile(routesFilePath: Path): RoutesFile {
    val reader = createXmlReader(routesFilePath)

    val vTypes = mutableListOf<VehicleTypeDefinition>()
    val routes = mutableListOf<RouteDefinition>()
    val vehicles = mutableListOf<VehicleDefinition>()
    val flows = mutableListOf<FlowDefinition>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue

      when (reader.localName) {
        "vType" -> vTypes += parseVType(reader)
        "route" -> {
          // Top-level <route id="..."> only (inline vehicle routes are handled inside vehicle
          // parsing)
          val routeId = reader.attribute("id") ?: ""
          if (routeId.isNotBlank()) routes += parseRouteDefinition(reader, routeId)
        }
        "vehicle" -> vehicles += parseVehicleDefinition(reader)
        "flow" -> flows += parseFlowDefinition(reader)
      }
    }

    reader.close()
    return RoutesFile(vehicleTypes = vTypes, routes = routes, vehicles = vehicles, flows = flows)
  }

  /**
   * Parses a SUMO vehicle types additional file (`*.add.xml`) into a [VehicleTypesFile].
   *
   * @param vTypesFilePath Path to the vehicle types additional file.
   * @return Parsed [VehicleTypesFile].
   */
  private fun parseVehicleTypesAddFile(vTypesFilePath: Path): VehicleTypesFile {
    val reader = createXmlReader(vTypesFilePath)
    val vTypes = mutableListOf<VehicleTypeDefinition>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue
      if (reader.localName == "vType") vTypes += parseVType(reader)
    }

    reader.close()
    return VehicleTypesFile(vehicleTypes = vTypes)
  }

  /**
   * Parses a `<vType ...>...</vType>` element.
   *
   * @param reader XML reader positioned at `<vType>`.
   * @return Parsed [VehicleTypeDefinition].
   */
  private fun parseVType(reader: XMLStreamReader): VehicleTypeDefinition {
    val typeId = reader.attribute("id") ?: ""
    val vClass = reader.attribute("vClass") ?: ""
    val minGap = reader.attribute("minGap")?.toDoubleOrNull() ?: 0.0
    val tau = reader.attribute("tau")?.toDoubleOrNull() ?: 0.0

    val rawAttributes =
        (0 until reader.attributeCount).associate { idx ->
          reader.getAttributeLocalName(idx) to (reader.getAttributeValue(idx) ?: "")
        }

    val params = mutableListOf<TypeParameter>()

    // consume until </vType>
    while (reader.hasNext()) {
      val ev = reader.next()
      if (ev == XMLStreamConstants.START_ELEMENT && reader.localName == "param") {
        params += parseTypeParam(reader)
      } else if (ev == XMLStreamConstants.END_ELEMENT && reader.localName == "vType") {
        break
      }
    }

    return VehicleTypeDefinition(
        typeId = typeId,
        vehicleClass = vClass,
        minGapMeters = minGap,
        tauSeconds = tau,
        parameters = params,
        rawAttributes = rawAttributes)
  }

  /** Parses a `<param key="..." value="..."/>` inside `<vType>`. */
  private fun parseTypeParam(reader: XMLStreamReader): TypeParameter =
      TypeParameter(key = reader.attribute("key") ?: "", value = reader.attribute("value") ?: "")

  /**
   * Parses a top-level `<route id="..." edges="..."/>` element.
   *
   * @param reader XML reader positioned at `<route>`.
   * @param routeId Route id.
   * @return Parsed [RouteDefinition].
   */
  private fun parseRouteDefinition(reader: XMLStreamReader, routeId: String): RouteDefinition {
    val edgesRaw = reader.attribute("edges") ?: ""
    return RouteDefinition(routeId = routeId, edgeIds = splitSpaceList(edgesRaw))
  }

  /**
   * Parses `<vehicle ...>...</vehicle>`.
   *
   * Supports inline `<route edges="..."/>` and/or stops.
   */
  private fun parseVehicleDefinition(reader: XMLStreamReader): VehicleDefinition {
    val vehicleId = reader.attribute("id") ?: ""
    val typeId = reader.attribute("type") ?: ""
    val depart = reader.attribute("depart")?.toDoubleOrNull() ?: 0.0
    val departLane = reader.attribute("departLane") ?: ""
    val departSpeed = reader.attribute("departSpeed") ?: ""

    var routeSpec: VehicleRouteSpecification = VehicleRouteSpecification.InlineEdges(emptyList())
    val stops = mutableListOf<StopDefinition>()

    while (reader.hasNext()) {
      val ev = reader.next()
      if (ev == XMLStreamConstants.START_ELEMENT && reader.localName == "route") {
        val edges = reader.attribute("edges") ?: ""
        routeSpec = VehicleRouteSpecification.InlineEdges(splitSpaceList(edges))
      } else if (ev == XMLStreamConstants.START_ELEMENT && reader.localName == "stop") {
        stops += parseStopDefinition(reader)
      } else if (ev == XMLStreamConstants.END_ELEMENT && reader.localName == "vehicle") {
        break
      }
    }

    return VehicleDefinition(
        vehicleId = vehicleId,
        vehicleTypeId = typeId,
        departTimeSeconds = depart,
        departLaneSpec = departLane,
        departSpeedSpec = departSpeed,
        route = routeSpec,
        stops = stops)
  }

  /** Parses `<flow ...>...</flow>`. */
  private fun parseFlowDefinition(reader: XMLStreamReader): FlowDefinition {
    val flowId = reader.attribute("id") ?: ""
    val begin = reader.attribute("begin")?.toDoubleOrNull() ?: 0.0
    val end = reader.attribute("end")?.toDoubleOrNull() ?: 0.0
    val typeId = reader.attribute("type") ?: ""
    val routeRef = reader.attribute("route") ?: ""
    val number = reader.attribute("number")?.toIntOrNull() ?: 0
    val departSpeed = reader.attribute("departSpeed") ?: ""

    // consume until </flow> (flows can contain children, but your file has none)
    while (reader.hasNext()) {
      val ev = reader.next()
      if (ev == XMLStreamConstants.END_ELEMENT && reader.localName == "flow") break
    }

    val routeSpec =
        if (routeRef.isNotBlank()) VehicleRouteSpecification.RouteReference(routeRef)
        else VehicleRouteSpecification.InlineEdges(emptyList())

    return FlowDefinition(
        flowId = flowId,
        beginTimeSeconds = begin,
        endTimeSeconds = end,
        vehicleTypeId = typeId,
        route = routeSpec,
        number = number,
        departSpeedSpec = departSpeed)
  }

  /** Parses `<stop .../>`. */
  private fun parseStopDefinition(reader: XMLStreamReader): StopDefinition {
    val rawAttributes =
        (0 until reader.attributeCount).associate { idx ->
          reader.getAttributeLocalName(idx) to (reader.getAttributeValue(idx) ?: "")
        }

    return StopDefinition(
        laneId = reader.attribute("lane") ?: "",
        endPosMeters = reader.attribute("endPos")?.toDoubleOrNull() ?: 0.0,
        untilTimeSeconds = reader.attribute("until")?.toDoubleOrNull() ?: 0.0,
        rawAttributes = rawAttributes)
  }

  /**
   * Parses a SUMO `.net.xml` file into a [RoadNetwork].
   *
   * Important: SUMO often lists `<edge>` elements before `<junction>` elements. Therefore edges are
   * parsed as [EdgeRaw] first and only resolved to [Edge] after all junctions are known.
   */
  private fun parseNet(netFilePath: Path): RoadNetwork {
    val reader = createXmlReader(netFilePath)

    var location: Location? = null

    val junctions = mutableListOf<Junction>()
    val junctionById = linkedMapOf<String, Junction>()

    val incomingLaneIdsByJunctionId = mutableMapOf<String, List<String>>()
    val internalLaneIdsByJunctionId = mutableMapOf<String, List<String>>()

    val edgeRaws = mutableListOf<EdgeRaw>()
    val connectionRaws = mutableListOf<ConnectionRaw>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue

      when (reader.localName) {
        "location" -> location = parseLocation(reader)

        "junction" -> {
          val raw = parseJunctionRaw(reader)
          junctions += raw.junction
          junctionById[raw.junction.junctionId] = raw.junction
          incomingLaneIdsByJunctionId[raw.junction.junctionId] = raw.incomingLaneIds
          internalLaneIdsByJunctionId[raw.junction.junctionId] = raw.internalLaneIds
        }

        "edge" -> edgeRaws += parseEdgeRaw(reader)

        "connection" -> connectionRaws += parseConnectionRaw(reader)
      }
    }

    reader.close()

    val edges: List<Edge> = resolveEdges(edgeRaws, junctionById)

    // Build lane lookup from resolved edges
    val laneById: Map<String, Lane> = buildMap {
      for (edge in edges) for (lane in edge.lanes) put(lane.laneId, lane)
    }

    // Fill junction incoming/internal lane pointers
    resolveJunctionLanePointers(
        junctions = junctions,
        laneById = laneById,
        incomingLaneIdsByJunctionId = incomingLaneIdsByJunctionId,
        internalLaneIdsByJunctionId = internalLaneIdsByJunctionId)

    val defaultLocation =
        Location(
            netOffset = Point(0.0f, 0.0f),
            convertedBoundary = BoundaryBox(0.0, 0.0, 0.0, 0.0),
            originalBoundary = BoundaryBox(0.0, 0.0, 0.0, 0.0),
            projection = Projection.None)

    val networkWithoutConnections =
        RoadNetwork(
            location = location ?: defaultLocation,
            junctions = junctions,
            edges = edges,
            connections = emptyList())

    // Resolve connections using lane ids AFTER all lanes exist
    val resolvedConnections: List<Connection> =
        connectionRaws.map { raw -> importConnection(raw, laneById) }

    return networkWithoutConnections.copy(connections = resolvedConnections)
  }

  /**
   * Parses an `<edge>...</edge>` element into an [EdgeRaw], including nested `<lane/>` children.
   *
   * @param reader XML stream reader positioned at `<edge>`.
   * @return Raw edge record.
   */
  private fun parseEdgeRaw(reader: XMLStreamReader): EdgeRaw {
    val edgeId = reader.attribute("id") ?: ""
    val fromId = reader.attribute("from") ?: ""
    val toId = reader.attribute("to") ?: ""
    val function = reader.attribute("function") ?: ""
    val priority = reader.attribute("priority")?.toIntOrNull() ?: 0

    val laneRaws = mutableListOf<LaneRaw>()

    while (reader.hasNext()) {
      val inner = reader.next()
      if (inner == XMLStreamConstants.START_ELEMENT && reader.localName == "lane") {
        laneRaws += parseLaneRaw(reader)
      } else if (inner == XMLStreamConstants.END_ELEMENT && reader.localName == "edge") {
        break
      }
    }

    return EdgeRaw(
        edgeId = edgeId,
        fromJunctionId = fromId,
        toJunctionId = toId,
        edgeFunction = function,
        edgePriority = priority,
        laneRaws = laneRaws)
  }

  /**
   * Parses a `<lane .../>` element into a [LaneRaw].
   *
   * @param reader XML stream reader positioned at `<lane>`.
   * @return Raw lane record.
   */
  private fun parseLaneRaw(reader: XMLStreamReader): LaneRaw =
      LaneRaw(
          laneId = reader.attribute("id") ?: "",
          laneIndex = reader.attribute("index")?.toIntOrNull() ?: 0,
          speedLimitMetersPerSecond = reader.attribute("speed")?.toFloatOrNull() ?: 0.0f,
          laneLengthMeters = reader.attribute("length")?.toFloatOrNull() ?: 0.0f,
          shapeRaw = reader.attribute("shape") ?: "")

  /**
   * Parses a `<junction .../>` element into a [JunctionRaw].
   *
   * The returned [Junction] is created immediately, but incoming/internal lane pointers are
   * resolved later when all lanes are known.
   *
   * @param reader XML stream reader positioned at `<junction>`.
   * @return Parsed [JunctionRaw].
   */
  private fun parseJunctionRaw(reader: XMLStreamReader): JunctionRaw {
    val junctionId = reader.attribute("id") ?: ""
    val junctionType = JunctionType.fromXml(reader.attribute("type") ?: "")
    val x = reader.attribute("x")?.toFloatOrNull() ?: 0.0f
    val y = reader.attribute("y")?.toFloatOrNull() ?: 0.0f
    val incLanesRaw = reader.attribute("incLanes") ?: ""
    val intLanesRaw = reader.attribute("intLanes") ?: ""
    val shapeRaw = reader.attribute("shape") ?: ""
    val shape = parseShape(shapeRaw)

    val junction =
        Junction(
            junctionId = junctionId,
            junctionType = junctionType,
            location = Point(x, y),
            shape = shape)

    return JunctionRaw(
        junction = junction,
        incomingLaneIds = splitSpaceList(incLanesRaw),
        internalLaneIds = splitSpaceList(intLanesRaw))
  }

  /**
   * Resolves raw edges into pointer-based [Edge] objects once junctions are known.
   *
   * @param edgeRaws Raw edges parsed from XML.
   * @param junctionById Junction lookup map.
   * @return Resolved edges.
   */
  private fun resolveEdges(
      edgeRaws: List<EdgeRaw>,
      junctionById: Map<String, Junction>
  ): List<Edge> {
    val edges = mutableListOf<Edge>()

    for (raw in edgeRaws) {
      val fromJunction =
          junctionById[raw.fromJunctionId]
              ?: run {
                // Internal edges often have empty from/to; that's fine.
                if (raw.fromJunctionId.isNotBlank()) {
                  warnings +=
                      "Unknown junction '${raw.fromJunctionId}' referenced by edge '${raw.edgeId}'."
                }
                Defaults.unknownJunction
              }

      val toJunction =
          junctionById[raw.toJunctionId]
              ?: run {
                if (raw.toJunctionId.isNotBlank()) {
                  warnings +=
                      "Unknown junction '${raw.toJunctionId}' referenced by edge '${raw.edgeId}'."
                }
                Defaults.unknownJunction
              }

      val edge =
          Edge(
              edgeId = raw.edgeId,
              fromJunction = fromJunction,
              toJunction = toJunction,
              edgeFunction = raw.edgeFunction,
              edgePriority = raw.edgePriority)

      // Create lanes with parentEdge pointer
      for (laneRaw in raw.laneRaws) {
        val lane =
            Lane(
                laneId = laneRaw.laneId,
                laneIndex = laneRaw.laneIndex,
                speedLimitMetersPerSecond = laneRaw.speedLimitMetersPerSecond,
                laneLengthMeters = laneRaw.laneLengthMeters,
                laneShape = parseShape(laneRaw.shapeRaw),
                parentEdge = edge)
        edge.lanes += lane
      }

      edges += edge
    }

    return edges
  }

  /**
   * Resolves and fills [Junction.incomingLanes] and [Junction.internalLanes] based on lane ids.
   *
   * @param junctions Junction objects to update.
   * @param laneById Lane lookup map.
   * @param incomingLaneIdsByJunctionId Incoming lane ids by junction id.
   * @param internalLaneIdsByJunctionId Internal lane ids by junction id.
   */
  private fun resolveJunctionLanePointers(
      junctions: List<Junction>,
      laneById: Map<String, Lane>,
      incomingLaneIdsByJunctionId: Map<String, List<String>>,
      internalLaneIdsByJunctionId: Map<String, List<String>>
  ) {
    for (junction in junctions) {
      junction.incomingLanes.clear()
      junction.internalLanes.clear()

      val incomingIds = incomingLaneIdsByJunctionId[junction.junctionId].orEmpty()
      val internalIds = internalLaneIdsByJunctionId[junction.junctionId].orEmpty()

      for (laneId in incomingIds) {
        val lane = laneById[laneId]
        if (lane != null) junction.incomingLanes += lane
        else
            warnings +=
                "Unresolved incoming laneId '$laneId' for junction '${junction.junctionId}'."
      }

      for (laneId in internalIds) {
        val lane = laneById[laneId]
        if (lane != null) junction.internalLanes += lane
        else
            warnings +=
                "Unresolved internal laneId '$laneId' for junction '${junction.junctionId}'."
      }
    }
  }

  /**
   * Parses a `<location .../>` element into a typed [Location].
   *
   * @param reader XML stream reader positioned at `<location>`.
   * @return Parsed [Location] (never null).
   */
  private fun parseLocation(reader: XMLStreamReader): Location {
    val netOffsetRaw = reader.attribute("netOffset") ?: "0.0,0.0"
    val convBoundaryRaw = reader.attribute("convBoundary") ?: "0.0,0.0,0.0,0.0"
    val origBoundaryRaw = reader.attribute("origBoundary") ?: "0.0,0.0,0.0,0.0"
    val projParameterRaw = reader.attribute("projParameter") ?: "!"

    return Location(
        netOffset = parsePoint(netOffsetRaw),
        convertedBoundary = parseBoundaryBox(convBoundaryRaw),
        originalBoundary = parseBoundaryBox(origBoundaryRaw),
        projection = parseProjection(projParameterRaw))
  }

  /**
   * Parses SUMO `projParameter`.
   *
   * @param raw Raw `projParameter` attribute.
   * @return Parsed [Projection].
   */
  private fun parseProjection(raw: String): Projection {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "!") return Projection.None

    // Typical format: "+proj=utm +zone=32 +datum=WGS84 +units=m +no_defs"
    val tokens =
        trimmed
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .mapNotNull { token ->
              val normalized = token.trim()
              if (!normalized.startsWith("+") || normalized.length == 1) return@mapNotNull null

              val withoutPlus = normalized.substring(1)
              val parts = withoutPlus.split("=", limit = 2)

              val key = parts[0].trim()
              if (key.isEmpty()) return@mapNotNull null

              val value =
                  if (parts.size == 1) {
                    ProjValue.Flag
                  } else {
                    val rawValue = parts[1].trim()
                    rawValue.toDoubleOrNull()?.let { ProjValue.Number(it) }
                        ?: ProjValue.Text(rawValue)
                  }

              ProjToken(key = key, value = value)
            }

    return Projection.Proj4(tokens)
  }

  /**
   * Parses a SUMO point attribute formatted as `"x,y"`.
   *
   * @param raw Raw attribute value.
   * @return Parsed [Point].
   */
  private fun parsePoint(raw: String): Point {
    val values = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
    if (values.size != 2) {
      warnings += "Invalid netOffset '$raw' in <location>; using (0,0)."
      return Point(0.0f, 0.0f)
    }
    return Point(values[0], values[1])
  }

  /**
   * Parses a SUMO boundary attribute formatted as `"minX,minY,maxX,maxY"`.
   *
   * @param raw Raw attribute value.
   * @return Parsed [BoundaryBox].
   */
  private fun parseBoundaryBox(raw: String): BoundaryBox {
    val values = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    if (values.size != 4) {
      warnings += "Invalid boundary '$raw' in <location>; using zeros."
      return BoundaryBox(0.0, 0.0, 0.0, 0.0)
    }
    return BoundaryBox(values[0], values[1], values[2], values[3])
  }

  /**
   * Parses a `<connection .../>` element into a raw record.
   *
   * The returned [ConnectionRaw] is resolved into an actual [Connection] object later via
   * [importConnection] once all lanes are known.
   *
   * @param reader XML stream reader positioned at `<connection>`.
   * @return Raw connection record.
   */
  private fun parseConnectionRaw(reader: XMLStreamReader): ConnectionRaw =
      ConnectionRaw(
          fromEdgeId = reader.attribute("from") ?: "",
          toEdgeId = reader.attribute("to") ?: "",
          fromLaneIndex = reader.attribute("fromLane")?.toIntOrNull() ?: 0,
          toLaneIndex = reader.attribute("toLane")?.toIntOrNull() ?: 0,
          viaLaneId = reader.attribute("via") ?: "",
          directionRaw = reader.attribute("dir") ?: "",
          signalStateRaw = reader.attribute("state") ?: "")

  /**
   * Resolves a [ConnectionRaw] into a [Connection] by mapping ids to actual [Lane] pointers.
   *
   * SUMO encodes the participating lanes by edge id + lane index:
   * - incoming lane id: `"<fromEdgeId>_<fromLaneIndex>"`
   * - outgoing lane id: `"<toEdgeId>_<toLaneIndex>"`
   *
   * @param raw Raw connection record.
   * @param laneById Lane lookup map.
   * @return Resolved [Connection].
   */
  private fun importConnection(raw: ConnectionRaw, laneById: Map<String, Lane>): Connection {
    val incomingLaneId = "${raw.fromEdgeId}_${raw.fromLaneIndex}"
    val outgoingLaneId = "${raw.toEdgeId}_${raw.toLaneIndex}"

    val incomingLane =
        laneById[incomingLaneId]
            ?: error("Unknown incoming lane id '$incomingLaneId' referenced by <connection>.")
    val outgoingLane =
        laneById[outgoingLaneId]
            ?: error("Unknown outgoing lane id '$outgoingLaneId' referenced by <connection>.")

    val viaLane =
        raw.viaLaneId
            .takeIf { it.isNotBlank() }
            ?.let { id ->
              laneById[id] ?: error("Unknown via lane id '$id' referenced by <connection>.")
            }

    return Connection(
        incomingLane = incomingLane,
        outgoingLane = outgoingLane,
        viaLane = viaLane,
        direction = ConnectionDirection.fromXml(raw.directionRaw),
        signalState = ConnectionSignalState.fromXml(raw.signalStateRaw))
  }

  /**
   * Parses `export.xml` into ordered [TimeStep]s and resolves lane/edge pointers.
   *
   * Parsing is delegated to dedicated functions for each element:
   * - `<timestep>` -> [parseTimeStep]
   * - `<edge>` (inside timestep) -> [skipEdgeContainer] + [parseLaneInExport]
   * - `<vehicle>` -> [parseVehicle]
   */
  private fun parseExport(
      exportFilePath: Path,
      net: RoadNetwork,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile,
      collisionsByTickMillis: Map<Long, List<CollisionEventRaw>>,
  ): List<TimeStep> {
    val reader = createXmlReader(exportFilePath)
    val ticks = mutableListOf<TimeStep>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue
      if (reader.localName != "timestep") continue

      val parsedTick =
          parseTimeStep(reader, exportFilePath, net, routesFile, vTypesFile, collisionsByTickMillis)
      if (parsedTick != null) ticks += parsedTick
    }

    reader.close()
    return ticks
  }

  /**
   * Parses `collision.xml` into raw collision records.
   *
   * Vehicle pointers are resolved later per tick when `export.xml` has been parsed.
   *
   * @param collisionFilePath Path to the collision output.
   * @return Raw collision records.
   */
  private fun parseCollisionEvents(collisionFilePath: Path): List<CollisionEventRaw> {
    val reader = createXmlReader(collisionFilePath)
    val rawEvents = mutableListOf<CollisionEventRaw>()

    while (reader.hasNext()) {
      val eventType = reader.next()
      if (eventType != XMLStreamConstants.START_ELEMENT) continue
      if (reader.localName != "collision") continue

      rawEvents += parseCollision(reader)
    }

    reader.close()
    return rawEvents
  }

  /**
   * Parses a single `<collision .../>` element into a [CollisionEventRaw].
   *
   * @param reader XML stream reader positioned at `<collision>`.
   * @return Raw collision record.
   */
  private fun parseCollision(reader: XMLStreamReader): CollisionEventRaw {
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

    return CollisionEventRaw(
        tickMillis = secondsToMillis(timeSeconds),
        timeSeconds = timeSeconds,
        laneId = laneId,
        positionOnLaneMeters = pos,
        colliderId = colliderId,
        victimId = victimId,
        collisionType = collisionType,
        rawAttributes = rawAttributes)
  }

  /**
   * Parses a `<timestep ...>...</timestep>` block from `export.xml`.
   *
   * The reader must be positioned at the `<timestep>` start element when calling this function.
   *
   * @param reader XML stream reader positioned at `<timestep>`.
   * @param net Parsed road network for pointer resolution.
   * @param routesFile Parsed routes file for vehicle type inference.
   * @param vTypesFile Parsed vehicle types additional file for vehicle type inference.
   * @param collisionsByTickMillis Pre-bucketed collisions.
   * @return Parsed [TimeStep].
   */
  private fun parseTimeStep(
      reader: XMLStreamReader,
      exportFilePath: Path,
      net: RoadNetwork,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile,
      collisionsByTickMillis: Map<Long, List<CollisionEventRaw>>
  ): TimeStep? {
    val timeSeconds = reader.attribute("time")?.toFloatOrNull() ?: 0.0f
    val tickMillis = secondsToMillis(timeSeconds)
    val vehiclesInTick = mutableListOf<Vehicle>()

    while (reader.hasNext()) {
      val inner = reader.next()
      if (inner == XMLStreamConstants.START_ELEMENT && reader.localName == "edge") {
        skipEdgeContainer(reader, net, routesFile, vTypesFile, vehiclesInTick, timeSeconds)
      } else if (inner == XMLStreamConstants.END_ELEMENT && reader.localName == "timestep") {
        break
      }
    }

    val vehiclesById: Map<String, Vehicle> = vehiclesInTick.associateBy { it.vehicleId }
    if (tickMillis == 0L) {
      check(routesFile.vehicles.size == vehiclesInTick.size) {
        "At time=0s, expected ${routesFile.vehicles.size} vehicles from routes.xml, " +
            "but found ${vehiclesInTick.size} in export.xml."
      }
    }
    val collisionsInTick =
        resolveCollisionsForTick(net, tickMillis, collisionsByTickMillis, vehiclesById)
    val ego = resolveEgoVehicle(vehiclesInTick)

    if (ego == null) {
      return null
    }

    return TimeStep(
        identifier = "SUMO_TICK_$tickMillis",
        sourceIdentifier = exportFilePath.fileName.toString(),
        tickTimeMillis = tickMillis,
        vehiclesInTick = vehiclesInTick,
        collisionsInTick = collisionsInTick,
        ego = ego)
  }

  /**
   * Consumes an `<edge>...</edge>` block inside `export.xml` and extracts vehicles from nested
   * lanes.
   *
   * @param reader XML stream reader positioned at `<edge>`.
   * @param net Parsed road network.
   * @param routesFile Parsed routes file for vehicle type inference.
   * @param vTypesFile Parsed vehicle types additional file for vehicle type inference.
   * @param vehiclesInTick Mutable output list for all vehicles in the current tick.
   * @param timeSeconds Current timestep time in seconds (for warnings).
   */
  private fun skipEdgeContainer(
      reader: XMLStreamReader,
      net: RoadNetwork,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile,
      vehiclesInTick: MutableList<Vehicle>,
      timeSeconds: Float
  ) {
    while (reader.hasNext()) {
      val edgeInner = reader.next()
      if (edgeInner == XMLStreamConstants.START_ELEMENT && reader.localName == "lane") {
        parseLaneInExport(reader, net, routesFile, vTypesFile, vehiclesInTick, timeSeconds)
      } else if (edgeInner == XMLStreamConstants.END_ELEMENT && reader.localName == "edge") {
        break
      }
    }
  }

  /**
   * Consumes a `<lane>...</lane>` block inside `export.xml` and extracts nested `<vehicle/>`
   * elements.
   *
   * @param reader XML stream reader positioned at `<lane>`.
   * @param net Parsed road network.
   * @param routesFile Parsed routes file for vehicle type inference.
   * @param vTypesFile Parsed vehicle types additional file for vehicle type inference.
   * @param vehiclesInTick Mutable output list of vehicles for this tick.
   * @param timeSeconds Current timestep time in seconds (for warnings).
   */
  private fun parseLaneInExport(
      reader: XMLStreamReader,
      net: RoadNetwork,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile,
      vehiclesInTick: MutableList<Vehicle>,
      timeSeconds: Float
  ) {
    val laneId = reader.attribute("id") ?: ""
    val lane =
        net.laneById[laneId]
            ?: run {
              warnings += "Unresolved laneId '$laneId' in export.xml at time=$timeSeconds"
              Defaults.unknownLane
            }

    val edge: Edge = lane.parentEdge

    while (reader.hasNext()) {
      val laneInner = reader.next()
      if (laneInner == XMLStreamConstants.START_ELEMENT && reader.localName == "vehicle") {
        vehiclesInTick += parseVehicle(reader, routesFile, vTypesFile, lane, edge)
      } else if (laneInner == XMLStreamConstants.END_ELEMENT && reader.localName == "lane") {
        break
      }
    }
  }

  /**
   * Parses a `<vehicle .../>` element from `export.xml` into a [Vehicle].
   *
   * @param reader XML stream reader positioned at `<vehicle>`.
   * @param routesFile Parsed routes file for vehicle type inference.
   * @param vTypesFile Parsed vehicle types additional file for vehicle type inference.
   * @param lane Resolved lane pointer.
   * @param edge Resolved edge pointer.
   * @return Parsed [Vehicle].
   */
  private fun parseVehicle(
      reader: XMLStreamReader,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile,
      lane: Lane,
      edge: Edge
  ): Vehicle {
    val vehicleId = reader.attribute("id") ?: ""
    val pos = reader.attribute("pos")?.toFloatOrNull() ?: 0.0f
    val speed = reader.attribute("speed")?.toFloatOrNull() ?: 0.0f

    val typeDefinition = inferVehicleTypeDefinition(vehicleId, routesFile, vTypesFile)

    return Vehicle(
        vehicleId = vehicleId,
        vehicleType = VehicleType(typeDefinition),
        currentLane = lane,
        currentEdge = edge,
        positionOnLaneMeters = pos,
        speedMetersPerSecond = speed)
  }

  /**
   * Resolves collisions for a particular tick and maps them to [CollisionEvent] objects.
   *
   * @param net Parsed road network.
   * @param tickMillis Tick timestamp (ms).
   * @param collisionsByTickMillis Pre-bucketed raw collisions.
   * @param vehiclesById Vehicles present in the tick, indexed by id.
   * @return Collision events for the tick.
   */
  private fun resolveCollisionsForTick(
      net: RoadNetwork,
      tickMillis: Long,
      collisionsByTickMillis: Map<Long, List<CollisionEventRaw>>,
      vehiclesById: Map<String, Vehicle>
  ): List<CollisionEvent> =
      collisionsByTickMillis[tickMillis].orEmpty().map { raw ->
        val lane = net.laneById[raw.laneId].orDefaultLane(raw.laneId)
        val edge: Edge = lane.parentEdge
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

  /**
   * Resolves the ego vehicle for a tick.
   *
   * @param vehiclesInTick Vehicles present in the tick.
   * @return Ego [Vehicle] (placeholder if none exist).
   */
  private fun resolveEgoVehicle(
      vehiclesInTick: List<Vehicle>,
  ): Vehicle? = vehiclesInTick.find { it.vehicleType.typeId == "ego" }

  /**
   * Converts SUMO seconds into milliseconds using rounding.
   *
   * @param seconds Time in seconds.
   * @return Time in milliseconds.
   */
  private fun secondsToMillis(seconds: Float): Long = round(seconds * 1000.0f).toLong()

  /**
   * Splits a SUMO space-separated list attribute into a list.
   *
   * @param value Raw attribute value.
   * @return Parsed list (empty if blank).
   */
  private fun splitSpaceList(value: String): List<String> =
      value.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

  /**
   * Infers the vehicle type definition for a SUMO vehicle id.
   *
   * Type inference is done using the imported routes file:
   * 1) If the id matches an explicit `<vehicle id="...">`, use its `type`.
   * 2) If the id matches the flow pattern `"<flowId>.<n>"`, use the flow's `type`.
   *
   * Type resolution is then done by looking up the referenced `vType` in:
   * - the routes file (if it still contains `vType`s), and
   * - the additional vTypes file (`vTypes.add.xml`).
   *
   * @param vehicleId Vehicle id from `export.xml`.
   * @param routesFile Parsed routes file (vehicles/flows).
   * @param vTypesFile Parsed vTypes file (additional vType definitions).
   * @return The resolved [VehicleTypeDefinition] (never null).
   */
  private fun inferVehicleTypeDefinition(
      vehicleId: String,
      routesFile: RoutesFile,
      vTypesFile: VehicleTypesFile
  ): VehicleTypeDefinition {

    // Helper: resolve a typeId by searching both sources.
    fun resolveTypeId(typeId: String): VehicleTypeDefinition {
      // Prefer vTypes defined in routes file (local override semantics), then fall back to
      // add-file.
      val fromRoutes = routesFile.vehicleTypeById[typeId]
      if (fromRoutes != null) return fromRoutes

      val fromAdd = vTypesFile.vehicleTypeById[typeId]
      if (fromAdd != null) return fromAdd

      warnings += "Referenced vType '$typeId' is not defined in .rou.xml nor in vTypes.add.xml."
      return Defaults.unknownVehicleTypeDefinition
    }

    // 1) explicit <vehicle id="...">
    val explicit = routesFile.vehicleById[vehicleId]
    if (explicit != null) {
      if (explicit.vehicleTypeId.isBlank()) {
        warnings += "Vehicle '$vehicleId' has no 'type' attribute in .rou.xml; using UNKNOWN_TYPE."
        return Defaults.unknownVehicleTypeDefinition
      }
      return resolveTypeId(explicit.vehicleTypeId)
    }

    // 2) flow vehicles: flowId.N
    val dotIndex = vehicleId.indexOf('.')
    if (dotIndex > 0) {
      val flowId = vehicleId.substring(0, dotIndex)
      val flow = routesFile.flowById[flowId]
      if (flow != null) {
        if (flow.vehicleTypeId.isBlank()) {
          warnings += "Flow '$flowId' has no 'type' attribute in .rou.xml; using UNKNOWN_TYPE."
          return Defaults.unknownVehicleTypeDefinition
        }
        return resolveTypeId(flow.vehicleTypeId)
      }
    }

    warnings += "Could not infer vType for vehicle '$vehicleId' from .rou.xml; using UNKNOWN_TYPE."
    return Defaults.unknownVehicleTypeDefinition
  }

  /**
   * Produces a placeholder vehicle for collision linking when the vehicle is not present in a tick.
   *
   * @param vehicleId Vehicle id.
   * @param lane Lane pointer.
   * @param edge Edge pointer.
   * @return Placeholder [Vehicle].
   */
  private fun placeholderVehicle(vehicleId: String, lane: Lane, edge: Edge): Vehicle =
      Vehicle(
          vehicleId = vehicleId,
          vehicleType = Defaults.unknownVehicleType,
          currentLane = lane,
          currentEdge = edge,
          positionOnLaneMeters = 0.0f,
          speedMetersPerSecond = 0.0f)

  /**
   * Returns a lane if resolvable, else a placeholder lane with warnings.
   *
   * @param laneId Lane id used for warning message.
   * @return Non-null [Lane].
   * @receiver Resolved lane or null.
   */
  private fun Lane?.orDefaultLane(laneId: String): Lane =
      this
          ?: run {
            warnings += "Unresolved laneId '$laneId' in collision.xml; using UNKNOWN_LANE."
            Defaults.unknownLane
          }
}
