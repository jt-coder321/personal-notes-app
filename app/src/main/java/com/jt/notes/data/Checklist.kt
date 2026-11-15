package com.jt.notes.data

data class CheckItem(val text: String, val checked: Boolean)

private const val OPEN = "[ ] "
private const val DONE = "[x] "

fun parseItems(body: String): List<CheckItem> {
    if (body.isEmpty()) return emptyList()
    return body.split("\n").map { line ->
        when {
            line.startsWith(DONE) -> CheckItem(line.substring(DONE.length), true)
            line.startsWith(OPEN) -> CheckItem(line.substring(OPEN.length), false)
            line == DONE.trim() -> CheckItem("", true)
            line == OPEN.trim() -> CheckItem("", false)
            else -> CheckItem(line, false)
        }
    }
}

fun itemsToBody(items: List<CheckItem>): String =
    items.joinToString("\n") { (if (it.checked) DONE else OPEN) + it.text.replace("\n", " ") }

/** Plain text to checklist: every non-empty line becomes an open item. */
fun textToChecklistBody(text: String): String {
    val items = text.split("\n").filter { it.isNotBlank() }.map { CheckItem(it, false) }
    return itemsToBody(items.ifEmpty { listOf(CheckItem("", false)) })
}

/** Checklist to plain text: drops the checkboxes, keeps the lines. */
fun checklistBodyToText(body: String): String =
    parseItems(body).joinToString("\n") { it.text }

fun Note.isBlank(): Boolean =
    title.isBlank() && if (isChecklist) parseItems(body).all { it.text.isBlank() } else body.isBlank()
