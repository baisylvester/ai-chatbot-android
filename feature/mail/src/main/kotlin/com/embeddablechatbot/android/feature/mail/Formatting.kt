package com.embeddablechatbot.android.feature.mail

/** The composer's formatting buttons. The server renders this limited Markdown as escaped HTML. */
enum class TextStyleMark { Bold, Italic, Bullets, Numbers, Link }

/**
 * Applies [style] to the selection [start, end) of [text]. Returns the new text and the selection to
 * show afterwards. With nothing selected, inline styles insert an empty marker pair at the cursor.
 */
fun applyMark(text: String, start: Int, end: Int, style: TextStyleMark): Pair<String, Pair<Int, Int>> {
    val selected = text.substring(start, end)
    fun wrap(left: String, right: String = left): Pair<String, Pair<Int, Int>> =
        text.substring(0, start) + left + selected + right + text.substring(end) to (start + left.length to end + left.length)
    return when (style) {
        TextStyleMark.Bold -> wrap("**")
        TextStyleMark.Italic -> wrap("*")
        TextStyleMark.Link -> {
            val label = selected.ifBlank { "link text" }
            val inserted = "[$label](https://)"
            val urlStart = start + label.length + 3
            text.substring(0, start) + inserted + text.substring(end) to (urlStart to urlStart + 8)
        }
        TextStyleMark.Bullets, TextStyleMark.Numbers -> {
            // Prefix every line touched by the selection.
            val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0 || it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
            val lines = text.substring(lineStart, lineEnd).split('\n')
            val marked = lines.mapIndexed { i, line ->
                val bare = line.replace(Regex("^\\s*(?:[-*•]|\\d{1,3}[.)])\\s+"), "")
                if (style == TextStyleMark.Bullets) "- $bare" else "${i + 1}. $bare"
            }.joinToString("\n")
            val result = text.substring(0, lineStart) + marked + text.substring(lineEnd)
            result to (lineStart + marked.length to lineStart + marked.length)
        }
    }
}
