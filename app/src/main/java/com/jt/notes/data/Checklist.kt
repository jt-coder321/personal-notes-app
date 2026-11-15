package com.jt.notes.data

/** One line of a checklist. [indent] is 0 for a normal item and 1 for a sub-item. */
data class CheckItem(val text: String, val checked: Boolean, val indent: Int = 0)

private const val OPEN = "[ ] "
private const val DONE = "[x] "
private const val INDENT = "  "

fun parseItems(body: String): List<CheckItem> {
    if (body.isEmpty()) return emptyList()
    return body.split("\n").map { raw ->
        val indented = raw.startsWith(INDENT + "[")
        val line = if (indented) raw.substring(INDENT.length) else raw
        val indent = if (indented) 1 else 0
        when {
            line.startsWith(DONE) -> CheckItem(line.substring(DONE.length), true, indent)
            line.startsWith(OPEN) -> CheckItem(line.substring(OPEN.length), false, indent)
            line == DONE.trim() -> CheckItem("", true, indent)
            line == OPEN.trim() -> CheckItem("", false, indent)
            else -> CheckItem(raw, false, 0)
        }
    }
}

fun itemsToBody(items: List<CheckItem>): String =
    items.joinToString("\n") {
        (if (it.indent > 0) INDENT else "") + (if (it.checked) DONE else OPEN) + it.text.replace("\n", " ")
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
