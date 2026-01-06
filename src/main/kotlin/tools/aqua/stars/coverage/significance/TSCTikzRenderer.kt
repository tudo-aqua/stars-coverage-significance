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

package tools.aqua.stars.coverage.significance

import tools.aqua.stars.core.tsc.TSC
import tools.aqua.stars.core.tsc.node.TSCBoundedNode
import tools.aqua.stars.core.tsc.node.TSCLeafNode
import tools.aqua.stars.core.tsc.node.TSCNode
import tools.aqua.stars.core.types.EntityType
import tools.aqua.stars.core.types.TickDataType
import tools.aqua.stars.core.types.TickDifference
import tools.aqua.stars.core.types.TickUnit

object TSCTikzRenderer {

  enum class BoundsStyle {
    NONE,
    LETTER_WHEN_POSSIBLE, // A / X / O, else l..u
    RANGE_ALWAYS, // always l..u
  }

  data class Options(
      // Global styling
      val font: String = "\\small",

      // Forest layout controls (these are the important ones for overlap)
      val levelSep: String = "25mm", // forest: l sep
      val siblingSep: String = "6mm", // forest: s sep
      val textWidth: String? = "55mm", // wrap long labels; set null to disable

      // Node label formatting
      val boundsStyle: BoundsStyle = BoundsStyle.LETTER_WHEN_POSSIBLE,
      val escapeNodeLabels: Boolean = true,

      // Edge styling
      val dashedEdgesFromAllToNonLeafBounded: Boolean = true,

      // Optional edge labels (e.g., \ref{...})
      val edgeLabels: Map<Pair<String, String>, String> = emptyMap(),
      val edgeLabelNodeOptions: String = "midway,above",
      val includeLibraryHintComment: Boolean = true,
  )

  fun <
      E : EntityType<E, T, U, D>,
      T : TickDataType<E, T, U, D>,
      U : TickUnit<U, D>,
      D : TickDifference<D>,
  > render(tsc: TSC<E, T, U, D>, options: Options = Options()): String {

    val sb = StringBuilder()

    sb.appendLine("\\documentclass{standalone}")
    sb.appendLine("\\usepackage{tikz}")
    sb.appendLine("\\usepackage{forest}")
    sb.appendLine("\\begin{document}")

    // Define a stable forest style first (avoids \forestrset parse issues inside the environment)
    sb.appendLine("\\forestset{")
    sb.appendLine("  tsc/.style={")
    sb.appendLine("    for tree={")
    sb.appendLine("      grow=east,") // rightwards
    sb.appendLine("      parent anchor=east,") // outgoing at east
    sb.appendLine("      child anchor=west,") // incoming at west
    sb.appendLine("      align=left,")
    sb.appendLine("      anchor=west,")
    sb.appendLine("      font=${options.font},")
    sb.appendLine("      edge={draw},")
    sb.appendLine("    }")
    sb.appendLine("  }")
    sb.appendLine("}")
    sb.appendLine()

    sb.appendLine("\\begin{forest} tsc")
    emitForestNode(sb, tsc.rootNode, parent = null, options = options, indent = "")
    sb.appendLine()
    sb.appendLine("\\end{forest}")
    sb.appendLine("\\end{document}")

    return sb.toString()
  }

  private fun emitForestNode(
      sb: StringBuilder,
      node: TSCNode<*, *, *, *>,
      parent: TSCNode<*, *, *, *>?,
      options: Options,
      indent: String,
  ) {
    val label = formatNodeLabel(node, options)

    // Root or child node opening
    sb.append(indent)
    sb.append("[{")
    sb.append(label)
    sb.append("}")

    // If this node has a parent, we can attach per-edge attributes on *this* bracket.
    if (parent != null) {
      val dashed = options.dashedEdgesFromAllToNonLeafBounded && shouldDashedEdge(parent, node)

      val edgeLabel = options.edgeLabels[parent.label to node.label]

      if (dashed || edgeLabel != null) {
        if (dashed) {
          sb.append(", edge={draw,dashed}")
        }
        if (edgeLabel != null) {
          // Edge label is treated as LaTeX (not escaped)
          sb.append(", edge label={node[${options.edgeLabelNodeOptions}] {")
          sb.append(edgeLabel)
          sb.append("}}")
        }
      }
    }

    // Children
    if (node.edges.isNotEmpty()) {
      for (edge in node.edges) {
        sb.appendLine()
        emitForestNode(
            sb, edge.destination, parent = node, options = options, indent = indent + "  ")
      }
      sb.appendLine()
      sb.append(indent)
      sb.append("]")
    } else {
      sb.append("]")
    }
  }

  private fun shouldDashedEdge(parent: TSCNode<*, *, *, *>, child: TSCNode<*, *, *, *>): Boolean {
    val parentAll =
        parent is TSCBoundedNode<*, *, *, *> &&
            parent.edges.isNotEmpty() &&
            parent.bounds.first == parent.edges.size &&
            parent.bounds.second == parent.edges.size

    val childIsNonLeafBounded = child is TSCBoundedNode<*, *, *, *> && child.edges.isNotEmpty()

    return parentAll && childIsNonLeafBounded
  }

  private fun formatNodeLabel(node: TSCNode<*, *, *, *>, options: Options): String {
    val baseRaw = node.label
    val base = if (options.escapeNodeLabels) escapeLatex(baseRaw) else baseRaw

    if (options.boundsStyle == BoundsStyle.NONE) return base
    if (node is TSCLeafNode<*, *, *, *>) return base

    val bounded = node as? TSCBoundedNode<*, *, *, *> ?: return base
    val (l, u) = bounded.bounds
    val n = bounded.edges.size

    val marker =
        when (options.boundsStyle) {
          BoundsStyle.RANGE_ALWAYS -> "$l..$u"
          BoundsStyle.LETTER_WHEN_POSSIBLE ->
              when {
                l == u && l == n -> "A" // all
                l == u && l == 1 -> "X" // exclusive
                l == 0 && u == n -> "O" // optional
                else -> "$l..$u"
              }
          BoundsStyle.NONE -> ""
        }

    return if (marker.isBlank()) base else "$base ($marker)"
  }

  private fun escapeLatex(s: String): String {
    // Practical escaping for node text in forest/TikZ
    return buildString(s.length) {
      for (ch in s) {
        append(
            when (ch) {
              '\\' -> "\\textbackslash{}"
              '{' -> "\\{"
              '}' -> "\\}"
              '$' -> "\\$"
              '&' -> "\\&"
              '%' -> "\\%"
              '#' -> "\\#"
              '_' -> "\\_"
              '~' -> "\\textasciitilde{}"
              '^' -> "\\textasciicircum{}"
              '[' -> "{[}" // forest uses [] for structure; keep labels safe
              ']' -> "{]}"
              '\n' -> " \\\\ "
              else -> ch
            })
      }
    }
  }
}
