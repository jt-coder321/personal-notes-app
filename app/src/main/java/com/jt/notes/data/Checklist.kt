package com.jt.notes.data

/** One line of a checklist. [indent] is 0 for a normal item and 1 for a sub-item. [due] is an optional due date, "YYYY-MM-DD". */
data class CheckItem(val text: String, val checked: Boolean, val indent: Int = 0, val due: String? = null)

private const val OPEN = "[ ] "
private const val DONE = "[x] "
private const val INDENT = "  "

// A due date rides at the end of an item's text as " @due(2026-11-20)".
// It is stripped on parse and re-appended on serialize, so it never shows
// as item text and round-trips through parseItems/itemsToBody. Lines
// without the marker parse exactly as before.
private val DUE_SUFFIX = Regex(""" @due\((\d{4}-\d{2}-\d{2})\)$""")

private fun stripDue(text: String): Pair<String, String?> {
    val match = DUE_SUFFIX.find(text) ?: return text to null
    return text.substring(0, match.range.first) to match.groupValues[1]
}

fun parseItems(body: String): List<CheckItem> {
    if (body.isEmpty()) return emptyList()
    return body.split("\n").map { raw ->
        val indented = raw.startsWith(INDENT + "[")
        val line = if (indented) raw.substring(INDENT.length) else raw
        val indent = if (indented) 1 else 0
        when {
            line.startsWith(DONE) -> {
                val (text, due) = stripDue(line.substring(DONE.length))
                CheckItem(text, true, indent, due)
            }
            line.startsWith(OPEN) -> {
                val (text, due) = stripDue(line.substring(OPEN.length))
                CheckItem(text, false, indent, due)
            }
            line == DONE.trim() -> CheckItem("", true, indent)
            line == OPEN.trim() -> CheckItem("", false, indent)
            else -> CheckItem(raw, false, 0)
        }
    }
}

fun itemsToBody(items: List<CheckItem>): String =
    items.joinToString("\n") {
        (if (it.indent > 0) INDENT else "") + (if (it.checked) DONE else OPEN) +
            it.text.replace("\n", " ") + (it.due?.let { due -> " @due($due)" } ?: "")
    }

/** Plain text to checklist: every non-empty line becomes an open item. */
fun textToChecklistBody(text: String): String {
    val items = text.split("\n").filter { it.isNotBlank() }.map { CheckItem(it.removePrefix("- "), false) }
    return itemsToBody(items.ifEmpty { listOf(CheckItem("", false)) })
}

/** Checklist to plain text: drops the checkboxes, keeps the lines. */
fun checklistBodyToText(body: String): String =
    parseItems(body).joinToString("\n") { it.text }

/** The same checklist with every box cleared, for reusing a list. */
fun clearChecks(body: String): String =
    itemsToBody(parseItems(body).map { it.copy(checked = false) })

fun Note.isBlank(): Boolean =
    title.isBlank() && if (isChecklist) parseItems(body).all { it.text.isBlank() } else body.isBlank()