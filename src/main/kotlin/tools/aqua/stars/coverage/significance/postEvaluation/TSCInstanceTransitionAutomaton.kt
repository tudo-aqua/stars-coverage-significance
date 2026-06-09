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

package tools.aqua.stars.coverage.significance.postEvaluation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.TSCInstanceTransition
import tools.aqua.stars.coverage.significance.utils.MonitorViolation

/**
 * Generates automaton representations of TSC-instance transitions:
 * - Graphviz DOT files (+ PNG if `dot` is on PATH) for every variant
 * - One combined interactive HTML viewer (Cytoscape.js) with all variants and an inspection panel
 */
object TSCInstanceTransitionAutomaton {

  fun generate(
      instanceIds: List<UUID>,
      labels: List<String>,
      transitions: List<TSCInstanceTransition>,
      idToIndex: Map<UUID, Int>,
      basePath: Path,
  ) {
    Files.createDirectories(basePath)

    // All variants: (name → matrix), built for heatmap and automaton alike
    val allVariants = linkedMapOf<String, Array<LongArray>>()

    for (excludeDiagonal in listOf(false, true)) {
      val suffix = if (excludeDiagonal) "_no_diagonal" else ""

      val overallKey = "overall$suffix"
      val overallMatrix =
          buildMatrix(labels.size, transitions, idToIndex, excludeDiagonal) { it.totalCount }
      allVariants[overallKey] = overallMatrix
      emitDotAndPng(labels, overallMatrix, basePath, "automaton_$overallKey")

      for (monitor in MonitorViolation.entries) {
        val key = "${monitor.name}$suffix"
        val matrix =
            buildMatrix(labels.size, transitions, idToIndex, excludeDiagonal) {
              it.monitorCounts[monitor] ?: 0L
            }
        allVariants[key] = matrix
        emitDotAndPng(labels, matrix, basePath, "automaton_$key")
      }
    }

    val htmlPath = basePath.resolve("automaton_viewer.html")
    htmlPath.writeText(buildHtml(instanceIds, labels, allVariants))
    println("Interactive automaton viewer: $htmlPath")
  }

  // ---------------------------------------------------------------------------
  // Matrix helpers
  // ---------------------------------------------------------------------------

  private fun buildMatrix(
      n: Int,
      transitions: List<TSCInstanceTransition>,
      idToIndex: Map<UUID, Int>,
      excludeDiagonal: Boolean,
      valueExtractor: (TSCInstanceTransition) -> Long,
  ): Array<LongArray> {
    val matrix = Array(n) { LongArray(n) }
    for (t in transitions) {
      val fi = idToIndex[t.fromInstanceId] ?: continue
      val ti = idToIndex[t.toInstanceId] ?: continue
      if (excludeDiagonal && fi == ti) continue
      matrix[fi][ti] = valueExtractor(t)
    }
    return matrix
  }

  // ---------------------------------------------------------------------------
  // DOT / Graphviz
  // ---------------------------------------------------------------------------

  private fun emitDotAndPng(
      labels: List<String>,
      matrix: Array<LongArray>,
      basePath: Path,
      name: String,
  ) {
    val dotPath = basePath.resolve("$name.dot")
    dotPath.writeText(buildDot(labels, matrix))
    tryRenderPng(dotPath, basePath.resolve("$name.png"))
  }

  private fun buildDot(labels: List<String>, matrix: Array<LongArray>): String = buildString {
    appendLine("digraph {")
    appendLine(
        "    graph [rankdir=LR fontname=\"Helvetica\" bgcolor=\"white\" splines=true overlap=false]")
    appendLine(
        "    node  [shape=circle fontname=\"Helvetica\" style=filled fillcolor=\"#c6dbef\" color=\"#08306b\" penwidth=1.5]")
    appendLine("    edge  [fontname=\"Helvetica\" fontsize=10 color=\"#555555\" penwidth=1.2]")
    appendLine()
    labels.forEach { label -> appendLine("    \"${esc(label)}\"") }
    appendLine()
    for ((fi, from) in labels.withIndex()) {
      for ((ti, to) in labels.withIndex()) {
        val count = matrix[fi][ti]
        if (count > 0L) appendLine("    \"${esc(from)}\" -> \"${esc(to)}\" [label=\"$count\"]")
      }
    }
    appendLine("}")
  }

  private fun tryRenderPng(dotFile: Path, pngFile: Path) {
    try {
      val proc =
          ProcessBuilder(
                  "dot",
                  "-Tpng",
                  "-o",
                  pngFile.toAbsolutePath().toString(),
                  dotFile.toAbsolutePath().toString())
              .redirectErrorStream(true)
              .start()
      val done = proc.waitFor(60L, TimeUnit.SECONDS)
      if (!done) {
        proc.destroyForcibly()
        println("Graphviz timed out for: $dotFile")
      } else if (proc.exitValue() != 0) {
        val out = proc.inputStream.bufferedReader().readText().trim()
        println("Graphviz error (exit ${proc.exitValue()}) for $dotFile: $out")
      }
    } catch (_: IOException) {
      println("Graphviz 'dot' not found on PATH – DOT file written but PNG skipped: $dotFile")
    }
  }

  // ---------------------------------------------------------------------------
  // Interactive HTML
  // ---------------------------------------------------------------------------

  private fun buildHtml(
      instanceIds: List<UUID>,
      labels: List<String>,
      variants: Map<String, Array<LongArray>>,
  ): String {
    val variantJs =
        variants.entries.joinToString(",\n        ") { (name, matrix) ->
          val nodes =
              labels.indices.joinToString(", ") { i ->
                "{ data: { id: '${esc(labels[i])}', label: '${esc(labels[i])}', uuid: '${instanceIds[i]}' } }"
              }
          val edges =
              buildList {
                    for ((fi, from) in labels.withIndex()) {
                      for ((ti, to) in labels.withIndex()) {
                        val count = matrix[fi][ti]
                        if (count > 0L)
                            add(
                                "{ data: { id: '${esc(from)}_${esc(to)}', source: '${esc(from)}', target: '${esc(to)}', label: '$count', count: $count } }")
                      }
                    }
                  }
                  .joinToString(", ")
          "'${esc(name)}': { nodes: [$nodes], edges: [$edges] }"
        }

    return htmlTemplate(variantJs)
  }

  private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

  // The HTML is built via concatenation so that Kotlin does not misinterpret JavaScript's
  // ${...} template-literal syntax as Kotlin string interpolations.
  @Suppress("LongMethod")
  private fun htmlTemplate(variantJs: String): String {
    val css =
        """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: Helvetica, Arial, sans-serif; display: flex; flex-direction: column; height: 100vh; background: #f4f6f9; }
        #toolbar { display: flex; align-items: center; gap: 12px; padding: 8px 16px; background: #1e3a5f; color: #fff; flex-shrink: 0; box-shadow: 0 2px 6px rgba(0,0,0,.35); }
        #toolbar h1 { font-size: 14px; font-weight: 700; letter-spacing: .03em; }
        #toolbar label { font-size: 13px; }
        #varsel { padding: 4px 8px; border-radius: 4px; border: none; font-size: 13px; cursor: pointer; min-width: 220px; }
        .btn { padding: 4px 12px; border-radius: 4px; border: none; background: #4a90d9; color: #fff; cursor: pointer; font-size: 13px; transition: background .15s; }
        .btn:hover { background: #357abd; }
        #hint { margin-left: auto; font-size: 12px; opacity: .55; }
        #main { display: flex; flex: 1; overflow: hidden; }
        #cy { flex: 1; background: #fff; }
        #panel { width: 300px; background: #fff; border-left: 1px solid #dde3ec; padding: 18px 16px; overflow-y: auto; font-size: 13px; flex-shrink: 0; }
        #panel h3 { font-size: 14px; font-weight: 700; color: #1e3a5f; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid #eaeef3; }
        .placeholder { color: #bbb; font-style: italic; font-size: 12px; }
        .prop { margin-bottom: 10px; }
        .prop .key { font-size: 10px; text-transform: uppercase; letter-spacing: .07em; color: #999; margin-bottom: 2px; }
        .prop .val { font-weight: 600; word-break: break-all; }
        .prop .val.mono { font-family: monospace; font-size: 11px; font-weight: 400; color: #444; }
        .section-title { margin: 14px 0 6px; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: #888; }
        table { width: 100%; border-collapse: collapse; font-size: 12px; }
        th, td { text-align: left; padding: 5px 6px; border-bottom: 1px solid #f0f3f7; }
        th { background: #f5f7fa; font-size: 10px; text-transform: uppercase; letter-spacing: .05em; color: #777; }
        td:last-child, th:last-child { text-align: right; }
        tr:hover td { background: #f9fbfc; }
        """
            .trimIndent()

    // JavaScript written without template literals so Kotlin does not misparse the $ signs.
    val js =
        """
        const variants = {
                $variantJs
        };

        const varsel = document.getElementById('varsel');
        Object.keys(variants).forEach(function(k) {
          const o = document.createElement('option');
          o.value = k;
          o.textContent = k.replace(/_no_diagonal/g, ' (no diag)').replace(/_/g, ' ');
          varsel.appendChild(o);
        });

        let cy;

        function layoutCfg() {
          return { name: 'dagre', rankDir: 'LR', nodeSep: 60, rankSep: 100, edgeSep: 10, padding: 30 };
        }
        function reLayout() { if (cy) cy.layout(layoutCfg()).run(); }

        function load(name) {
          const d = variants[name];
          if (cy) cy.destroy();
          cy = cytoscape({
            container: document.getElementById('cy'),
            elements: { nodes: d.nodes, edges: d.edges },
            layout: layoutCfg(),
            style: [
              { selector: 'node', style: {
                  label: 'data(label)', 'text-valign': 'center', 'text-halign': 'center',
                  'background-color': '#c6dbef', 'border-color': '#08306b', 'border-width': 2,
                  width: 52, height: 52, 'font-size': 13, 'font-weight': 'bold',
                  'min-zoomed-font-size': 6,
              }},
              { selector: 'node:selected', style: {
                  'background-color': '#4a90d9', 'border-color': '#1e3a5f', 'border-width': 3,
              }},
              { selector: 'node.hl', style: {
                  'background-color': '#f5d76e', 'border-color': '#c9970a', 'border-width': 3,
              }},
              { selector: 'edge', style: {
                  width: 2, 'line-color': '#888', 'target-arrow-color': '#888',
                  'target-arrow-shape': 'triangle', 'curve-style': 'bezier',
                  label: 'data(label)', 'font-size': 11, color: '#333',
                  'text-background-color': '#fff', 'text-background-opacity': 0.85,
                  'text-background-padding': '2px', 'min-zoomed-font-size': 5,
              }},
              { selector: 'edge:selected', style: {
                  'line-color': '#4a90d9', 'target-arrow-color': '#4a90d9', width: 3,
              }},
            ],
          });
          cy.on('tap', 'node', function(e) { showNode(e.target, d); });
          cy.on('tap', 'edge', function(e) { showEdge(e.target); });
          cy.on('tap', function(e) { if (e.target === cy) clearPanel(); });
        }

        function prop(key, val, mono) {
          const cls = 'val' + (mono ? ' mono' : '');
          return '<div class="prop"><div class="key">' + key + '</div><div class="' + cls + '">' + val + '</div></div>';
        }
        function tableHtml(headers, rows, emptyMsg) {
          if (!rows.length) return '<p class="placeholder">' + (emptyMsg || 'none') + '</p>';
          const ths = headers.map(function(h) { return '<th>' + h + '</th>'; }).join('');
          const trs = rows.map(function(r) {
            return '<tr>' + r.map(function(c) { return '<td>' + c + '</td>'; }).join('') + '</tr>';
          }).join('');
          return '<table><thead><tr>' + ths + '</tr></thead><tbody>' + trs + '</tbody></table>';
        }
        function fmt(n) { return Number(n).toLocaleString(); }

        function showNode(node, d) {
          const id = node.id();
          cy.elements().removeClass('hl');
          node.addClass('hl');
          const out = d.edges.filter(function(e) { return e.data.source === id; })
                             .sort(function(a,b) { return b.data.count - a.data.count; });
          const inn = d.edges.filter(function(e) { return e.data.target === id; })
                             .sort(function(a,b) { return b.data.count - a.data.count; });
          const totalOut = out.reduce(function(s,e) { return s + e.data.count; }, 0);
          const totalIn  = inn.reduce(function(s,e) { return s + e.data.count; }, 0);
          const outRows = out.map(function(e) { return ['→ ' + e.data.target, fmt(e.data.count)]; });
          const innRows = inn.map(function(e) { return ['← ' + e.data.source, fmt(e.data.count)]; });
          document.getElementById('panel').innerHTML =
            '<h3>Node ' + id + '</h3>' +
            prop('UUID', node.data('uuid'), true) +
            prop('Total outgoing count', fmt(totalOut)) +
            prop('Total incoming count', fmt(totalIn)) +
            '<div class="section-title">Outgoing transitions</div>' +
            tableHtml(['Target','Count'], outRows, 'No outgoing edges') +
            '<div class="section-title">Incoming transitions</div>' +
            tableHtml(['Source','Count'], innRows, 'No incoming edges');
        }

        function showEdge(edge) {
          cy.elements().removeClass('hl');
          document.getElementById('panel').innerHTML =
            '<h3>Transition</h3>' +
            prop('From', edge.data('source')) +
            prop('To',   edge.data('target')) +
            prop('Count', fmt(edge.data('count')));
        }

        function clearPanel() {
          cy.elements().removeClass('hl');
          document.getElementById('panel').innerHTML =
            '<h3>Selection</h3><p class="placeholder">Click a node or edge in the graph.</p>';
        }

        varsel.addEventListener('change', function() { load(varsel.value); });
        if (Object.keys(variants).length) load(Object.keys(variants)[0]);
        """
            .trimIndent()

    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>TSC Instance Transition Automaton</title>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.28.1/cytoscape.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/dagre/0.8.5/dagre.min.js"></script>
  <script src="https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"></script>
  <style>$css</style>
</head>
<body>
<div id="toolbar">
  <h1>TSC Automaton</h1>
  <label for="varsel">Variant:</label>
  <select id="varsel"></select>
  <button class="btn" onclick="cy&amp;&amp;cy.fit()">Fit</button>
  <button class="btn" onclick="reLayout()">Re-layout</button>
  <span id="hint">Click a node or edge to inspect &nbsp;&middot;&nbsp; Scroll to zoom &nbsp;&middot;&nbsp; Drag to pan</span>
</div>
<div id="main">
  <div id="cy"></div>
  <div id="panel"><h3>Selection</h3><p class="placeholder">Click a node or edge in the graph.</p></div>
</div>
<script>$js</script>
</body>
</html>
    """
        .trimIndent()
  }
}
