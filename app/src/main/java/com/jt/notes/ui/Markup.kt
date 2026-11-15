package com.jt.notes.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/*
 * Simple formatting, stored as plain text so notes stay readable anywhere:
 *   "# " at the start of a line   heading
 *   "## "                         smaller heading
 *   "- "                          bullet
 *   **text**                      bold
 */

private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

/** Styles the text in place for the editor: every character stays, the marks are dimmed. */
fun styleMarkup(text: String, dim: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val dimStyle = SpanStyle(color = dim)
    var start = 0
    for (line in text.split("\n")) {
        val end = start + line.length
        when {
            line.startsWith("## ") -> {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp), start, end)
                addStyle(dimStyle, start, start + 2)
            }
            line.startsWith("# ") -> {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp), start, end)
                addStyle(dimStyle, start, start + 1)
            }
            line.startsWith("- ") -> addStyle(dimStyle, start, start + 1)
        }
        start = end + 1
    }
    for (match in BOLD.findAll(text)) {
        val range = match.range
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
        addStyle(dimStyle, range.first, range.first + 2)
        addStyle(dimStyle, range.last - 1, range.last + 1)
    }
}

class MarkupTransformation(private val dim: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styleMarkup(text.text, dim), OffsetMapping.Identity)
}

/** Renders the text for reading (note cards): the marks are removed and the styling applied. */
fun displayMarkup(text: String): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
    text.split("\n").forEachIndexed { index, raw ->
        if (index > 0) append("\n")
        var heading = false
        val line = when {
            raw.startsWith("## ") -> { heading = true; raw.substring(3) }
            raw.startsWith("# ") -> { heading = true; raw.substring(2) }
            raw.startsWith("- ") -> "• " + raw.substring(2)
            else -> raw
        }
        val lineStart = length
        var cursor = 0
        for (match in BOLD.findAll(line)) {
            append(line.substring(cursor, match.range.first))
            val boldStart = length
            append(match.groupValues[1])
            addStyle(bold, boldStart, length)
            cursor = match.range.last + 1
        }
        append(line.substring(cursor))
        if (heading) addStyle(bold, lineStart, length)
    }
}

// ----- editing helpers: each returns the new text and cursor -----

private fun lineStartOf(text: String, position: Int): Int =
    text.lastIndexOf('\n', (position - 1).coerceAtLeast(0).coerceAtMost(text.length)).let {
        if (position == 0) 0 else it + 1
    }

/** Adds or removes a line prefix such as "# " or "- " on the line holding the cursor. */
fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)
    val start = lineStartOf(text, cursor)
    val rest = text.substring(start)
    return when {
        rest.startsWith(prefix) -> {
            val newText = text.removeRange(start, start + prefix.length)
            TextFieldValue(newText, TextRange((cursor - prefix.length).coerceAtLeast(start)))
        }
        else -> {
            // Replace another prefix (for example a bullet becoming a heading).
            val other = listOf("## ", "# ", "- ").firstOrNull { rest.startsWith(it) }
            val cleaned = if (other != null) text.removeRange(start, start + other.length) else text
            val shift = prefix.length - (other?.length ?: 0)
            val newText = cleaned.substring(0, start) + prefix + cleaned.substring(start)
            TextFieldValue(newText, TextRange((cursor + shift).coerceIn(start + prefix.length, newText.length)))
        }
    }
}

/** Wraps the selection in ** **, or places the cursor between a new pair. */
fun toggleBold(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val a = value.selection.min.coerceIn(0, text.length)
    val b = value.selection.max.coerceIn(0, text.length)
    if (a == b) {
        val newText = text.substring(0, a) + "****" + text.substring(a)
        return TextFieldValue(newText, TextRange(a + 2))
    }
    val selected = text.substring(a, b)
    return if (selected.startsWith("**") && selected.endsWith("**") && selected.length >= 4) {
        val inner = selected.substring(2, selected.length - 2)
        TextFieldValue(text.substring(0, a) + inner + text.substring(b), TextRange(a, a + inner.length))
    } else {
        val newText = text.substring(0, a) + "**" + selected + "**" + text.substring(b)
        TextFieldValue(newText, TextRange(a, b + 4))
    }
}

// ----- links -----

enum class LinkKind { WEB, EMAIL, PHONE }

data class DetectedLink(val kind: LinkKind, val text: String)

/** Finds web addresses, email addresses and phone numbers in a note. */
fun detectLinks(text: String): List<DetectedLink> {
    if (text.isBlank()) return emptyList()
    val found = LinkedHashSet<DetectedLink>()
    val emails = Patterns.EMAIL_ADDRESS.toRegex().findAll(text).map { it.value }.toList()
    emails.forEach { found.add(DetectedLink(LinkKind.EMAIL, it)) }
    Patterns.WEB_URL.toRegex().findAll(text).map { it.value }
        .filter { url -> emails.none { it.contains(url) } && url.contains('.') }
        .forEach { found.add(DetectedLink(LinkKind.WEB, it)) }
    Patterns.PHONE.toRegex().findAll(text).map { it.value.trim() }
        .filter { number -> number.count { it.isDigit() } in 7..15 }
        .forEach { found.add(DetectedLink(LinkKind.PHONE, it)) }
    return found.take(8)
}
