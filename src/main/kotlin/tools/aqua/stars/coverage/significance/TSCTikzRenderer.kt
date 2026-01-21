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

/** Renderer for TSCs using LaTeX TikZ/forest package. */
object TSCTikzRenderer {

  /** Style for displaying bounds in node labels. */
  enum class BoundsStyle {
    /** No bounds shown. */
    NONE,
    /** Show letter markers when possible: A / X / O, else l..u. */
    LETTER_WHEN_POSSIBLE,
    /** Always show l..u. */
    RANGE_ALWAYS,
  }

  /**
   * Options for the [TSCTikzRenderer].
   *
   * @property font Font to use for node labels.
   * @property levelSep Forest level separation.
   * @property siblingSep Forest sibling separation.
   * @property textWidth Width of node labels, or null to disable wrapping.
   * @property boundsStyle Style for displaying bounds in node labels.
   * @property escapeNodeLabels Whether to escape node labels for LaTeX.
   * @property dashedEdgesFromAllToNonLeafBounded Whether to draw dashed edges from all to non-leaf
   *   bounded nodes.
   * @property edgeLabels Optional edge labels (e.g., \ref{...}).
   * @property edgeLabelNodeOptions Options for edge label nodes.
   * @property includeLibraryHintComment Whether to include a comment about the forest library
   *   version.
   */
  data class Options(
      val font: String = "\\small",
      val levelSep: String = "25mm",
      val siblingSep: String = "6mm",
      val textWidth: String? = "55mm",
      val boundsStyle: BoundsStyle = BoundsStyle.LETTER_WHEN_POSSIBLE,
      val escapeNodeLabels: Boolean = true,
      val dashedEdgesFromAllToNonLeafBounded: Boolean = true,
      val edgeLabels: Map<Pair<String, String>, String> = emptyMap(),
      val edgeLabelNodeOptions: String = "midway,above",
      val includeLibraryHintComment: Boolean = true,
  )

  /**
   * Renders the given [TSC] to a LaTeX TikZ/forest document.
   *
   * @param E - EntityType.
   * @param T - TickDataType.
   * @param U - TickUnit.
   * @param D - TickDifference.
   * @param tsc TSC to render.
   * @param options Rendering options.
   * @return LaTeX TikZ/forest document as a [String].
   */
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

  /**
   * Emits a forest node recursively.
   *
   * @param sb StringBuilder to append to.
   * @param node Current node.
   * @param parent Parent node, or null if root.
   * @param options Rendering options.
   * @param indent Current indentation.
   */
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

    // If this node has a parent, attach per-edge attributes on *this* bracket.
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

  /**
   * Determines whether the edge from [parent] to [child] should be dashed.
   *
   * @param parent Parent node.
   * @param child Child node.
   * @return True if the edge should be dashed.
   */
  private fun shouldDashedEdge(parent: TSCNode<*, *, *, *>, child: TSCNode<*, *, *, *>): Boolean {
    val parentAll =
        parent is TSCBoundedNode<*, *, *, *> &&
            parent.edges.isNotEmpty() &&
            parent.bounds.first == parent.edges.size &&
            parent.bounds.second == parent.edges.size

    val childIsNonLeafBounded = child is TSCBoundedNode<*, *, *, *> && child.edges.isNotEmpty()

    return parentAll && childIsNonLeafBounded
  }

  /**
   * Formats the label of a node, including bounds if applicable.
   *
   * @param node Node to format.
   * @param options Rendering options.
   * @return Formatted label.
   */
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
        }

    return if (marker.isBlank()) base else "$base ($marker)"
  }

  /**
   * Escapes special characters in a string for use in LaTeX.
   *
   * @param s String to escape.
   * @return Escaped string.
   */
  private fun escapeLatex(s: String): String =
      buildString(s.length) {
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
