package com.shijing.xomniclaw.agent.tools.device

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/browser/(all)
 *
 * OmniClaw adaptation: format accessibility tree output aligned with
 * Playwright's snapshot format (aria refs).
 */

object SnapshotFormatter {

    private fun formatVisibleText(node: RefNode, screenHeight: Int): String {
        val text = node.text
        if (text.isNullOrBlank()) return ""
        return " '" + text.trim().take(100) + "'"
    }

    /**
     * Compact format (default) — aligned with Playwright ai snapshot.
     * Shows role, text, ref, and interactive flags.
     */
    fun compact(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "  ".repeat(node.depth.coerceAtMost(6))
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.focusable && node.editable) { /* already shown as editable */ }
                else if (node.focusable) add("focusable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }
            val flagStr = if (flags.isNotEmpty()) " (${flags.joinToString(", ")})" else ""
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent${node.role}$textStr [ref=${node.ref}]$flagStr")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Interactive format — only interactive elements, with coordinates.
     * Best for quick action selection.
     */
    fun interactive(nodes: List<RefNode>, appName: String?, screenHeight: Int): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${appName ?: "Android"}] Interactive elements:")
        sb.appendLine()

        val interactiveNodes = nodes.filter { it.clickable || it.editable || it.scrollable }
        for (node in interactiveNodes) {
            val textStr = formatVisibleText(node, screenHeight)
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val state = buildList {
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(",").let { if (it.isNotEmpty()) " [$it]" else "" }
            sb.appendLine("[${node.ref}] ${node.role}$textStr$state ($cx, $cy)")
        }

        if (interactiveNodes.isEmpty()) {
            sb.appendLine("(no interactive elements found)")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Tree format — full hierarchy with bounds.
     */
    fun tree(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "│  ".repeat(node.depth.coerceAtMost(6))
            val prefix = if (node.depth > 0) "├─ " else ""
            val b = node.bounds
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(" ")
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent$prefix${node.role}$textStr [ref=${node.ref}] bounds=(${b.left},${b.top},${b.right},${b.bottom}) $flags".trimEnd())
        }

        return sb.toString().trimEnd()
    }
}
