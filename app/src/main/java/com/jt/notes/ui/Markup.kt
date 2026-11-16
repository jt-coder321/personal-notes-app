package com.jt.notes.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
private val STRIKE = Regex("~~(.+?)~~")
private val ITALIC = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|~~(.+?)~~|(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)")
private val LINE_PREFIX = Regex("^(## |# |- |> |\\d+\\. )")
private val NUMBERED = Regex("^(\\d+)\\. ")

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
            line.startsWith("> ") -> {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                addStyle(dimStyle, start, start + 1)
            }
            else -> NUMBERED.find(line)?.let { addStyle(dimStyle, start, start + it.value.length - 1) }
        }
        start = end + 1
    }
    for (match in BOLD.findAll(text)) {
        val range = match.range
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
        addStyle(dimStyle, range.first, range.first + 2)
        addStyle(dimStyle, range.last - 1, range.last + 1)
    }
    for (match in STRIKE.findAll(text)) {
        val range = match.range
        addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), range.first, range.last + 1)
        addStyle(dimStyle, range.first, range.first + 2)
        addStyle(dimStyle, range.last - 1, range.last + 1)
    }
    for (match in ITALIC.findAll(text)) {
        val range = match.range
        addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.first, range.last + 1)
        addStyle(dimStyle, range.first, range.first + 1)
        addStyle(dimStyle, range.last, range.last + 1)
    }
}

/** Marks which characters are formatting symbols that the editor hides (the text itself is unchanged). */
fun markupHidden(text: String): BooleanArray {
    val hidden = BooleanArray(text.length)
    var start = 0
    for (line in text.split("\n")) {
        val prefix = when {
            line.startsWith("## ") -> 3
            line.startsWith("# ") -> 2
            else -> 0
        }
        for (i in 0 until prefix) hidden[start + i] = true
        start += line.length + 1
    }
    fun hideEnds(range: IntRange, n: Int) {
        for (i in 0 until n) { hidden[range.first + i] = true; hidden[range.last - i] = true }
    }
    BOLD.findAll(text).forEach { hideEnds(it.range, 2) }
    STRIKE.findAll(text).forEach { hideEnds(it.range, 2) }
    ITALIC.findAll(text).forEach { hideEnds(it.range, 1) }
    return hidden
}

/** Characters shown in place of a list or quote marker. */
private fun markupSubstitutions(text: String): Map<Int, String> {
    val result = HashMap<Int, String>()
    var start = 0
    for (line in text.split("\n")) {
        if (line.startsWith("- ")) result[start] = "\u2022"
        else if (line.startsWith("> ")) result[start] = "\u258E"
        start += line.length + 1
    }
    return result
}

/** Shows formatted text without the symbols: bold looks bold, with no ** around it. */
class MarkupTransformation(private val dim: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val styled = styleMarkup(t, dim)
        val hidden = markupHidden(t)
        val subst = markupSubstitutions(t)
        val prefixEnds = HashSet<Int>().also { ends ->
            var start = 0
            for (line in t.split("\n")) {
                val n = when { line.startsWith("## ") -> 3; line.startsWith("# ") -> 2; else -> 0 }
                if (n > 0) ends.add(start + n)
                start += line.length + 1
            }
        }
        val visibleIndex = ArrayList<Int>()
        val originalToTransformed = IntArray(t.length + 1)
        val out = buildAnnotatedString {
            var runStart = -1
            fun flush(end: Int) {
                if (runStart >= 0) { append(styled.subSequence(runStart, end)); runStart = -1 }
            }
            for (i in t.indices) {
                originalToTransformed[i] = visibleIndex.size
                if (hidden[i]) { flush(i); continue }
                visibleIndex.add(i)
                val replacement = subst[i]
                if (replacement != null) {
                    flush(i)
                    withStyle(SpanStyle(color = dim)) { append(replacement) }
                } else if (runStart < 0) {
                    runStart = i
                }
            }
            flush(t.length)
        }
        originalToTransformed[t.length] = visibleIndex.size
        visibleIndex.add(t.length)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = originalToTransformed[offset.coerceIn(0, t.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val j = offset.coerceIn(0, visibleIndex.size - 1)
                val right = visibleIndex[j]
                // After a heading marker the cursor belongs to the heading text; elsewhere it stays inside
                // the formatted word, so typing at the end of bold text continues the bold.
                return if (j == 0 || right in prefixEnds) right else visibleIndex[j - 1] + 1
            }
        }
        return TransformedText(out, mapping)
    }
}

/**
 * Backspace next to a hidden symbol: remove the whole heading marker, or the visible letter before the
 * hidden symbols, so the user never has to delete characters they cannot see.
 */
fun fixHiddenDelete(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length - 1 || !new.selection.collapsed) return new
    val k = new.selection.start
    if (k < 0 || k >= old.text.length || old.text.removeRange(k, k + 1) != new.text) return new
    val hidden = markupHidden(old.text)
    if (!hidden[k]) return new
    val lineStart = old.text.lastIndexOf('\n', k - 1) + 1
    val rest = old.text.substring(lineStart)
    val prefix = when {
        rest.startsWith("## ") -> 3
        rest.startsWith("# ") -> 2
        else -> 0
    }
    if (k - lineStart < prefix) {
        return TextFieldValue(old.text.removeRange(lineStart, lineStart + prefix), TextRange(lineStart))
    }
    var j = k - 1
    while (j >= 0 && hidden[j]) j--
    if (j < 0) return TextFieldValue(old.text, TextRange(k))
    return TextFieldValue(old.text.removeRange(j, j + 1), TextRange(j))
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
            raw.startsWith("> ") -> "▎ " + raw.substring(2)
            else -> raw
        }
        val lineStart = length
        var cursor = 0
        for (match in INLINE.findAll(line)) {
            append(line.substring(cursor, match.range.first))
            val from = length
            val style = when {
                match.groups[1] != null -> { append(match.groupValues[1]); bold }
                match.groups[2] != null -> { append(match.groupValues[2]); SpanStyle(textDecoration = TextDecoration.LineThrough) }
                else -> { append(match.groupValues[3]); SpanStyle(fontStyle = FontStyle.Italic) }
            }
            addStyle(style, from, length)
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
        rest.startsWith(prefix) || (prefix == "1. " && NUMBERED.containsMatchIn(rest)) -> {
            val prefix = if (prefix == "1. ") NUMBERED.find(rest)?.value ?: prefix else prefix
            val newText = text.removeRange(start, start + prefix.length)
            TextFieldValue(newText, TextRange((cursor - prefix.length).coerceAtLeast(start)))
        }
        else -> {
            // Replace another prefix (for example a bullet becoming a heading).
            val other = LINE_PREFIX.find(rest)?.value
            val cleaned = if (other != null) text.removeRange(start, start + other.length) else text
            val shift = prefix.length - (other?.length ?: 0)
            val newText = cleaned.substring(0, start) + prefix + cleaned.substring(start)
            TextFieldValue(newText, TextRange((cursor + shift).coerceIn(start + prefix.length, newText.length)))
        }
    }
}

/** Wraps the selection in a mark such as ** or ~~, or places the cursor between a new pair. */
fun toggleWrap(value: TextFieldValue, mark: String): TextFieldValue {
    val text = value.text
    val n = mark.length
    val a = value.selection.min.coerceIn(0, text.length)
    val b = value.selection.max.coerceIn(0, text.length)
    if (a == b) {
        val newText = text.substring(0, a) + mark + mark + text.substring(a)
        return TextFieldValue(newText, TextRange(a + n))
    }
    if (a >= n && b + n <= text.length && text.substring(a - n, a) == mark && text.substring(b, b + n) == mark) {
        // The symbols are hidden in the editor, so the selection is usually just the words inside them.
        val newText = text.removeRange(b, b + n).removeRange(a - n, a)
        return TextFieldValue(newText, TextRange(a - n, b - n))
    }
    val selected = text.substring(a, b)
    return if (selected.length >= 2 * n + 1 && selected.startsWith(mark) && selected.endsWith(mark)) {
        val inner = selected.substring(n, selected.length - n)
        TextFieldValue(text.substring(0, a) + inner + text.substring(b), TextRange(a, a + inner.length))
    } else {
        val newText = text.substring(0, a) + mark + selected + mark + text.substring(b)
        TextFieldValue(newText, TextRange(a + n, b + n))
    }
}

fun toggleBold(value: TextFieldValue) = toggleWrap(value, "**")
fun toggleItalic(value: TextFieldValue) = toggleWrap(value, "*")
fun toggleStrike(value: TextFieldValue) = toggleWrap(value, "~~")

/**
 * Called after every edit. When the user just pressed Enter at the end of a bullet, number or quote
 * line, starts the next line with the same marker; pressing Enter on an empty marker line ends the list.
 */
fun continueList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val cursor = new.selection.start
    if (!new.selection.collapsed || new.text.length != old.text.length + 1) return new
    if (cursor < 1 || new.text[cursor - 1] != '\n') return new
    if (old.text != new.text.removeRange(cursor - 1, cursor)) return new
    val lineStart = new.text.lastIndexOf('\n', cursor - 2) + 1
    val line = new.text.substring(lineStart, cursor - 1)
    val marker = Regex("^(- |> |(\\d+)\\. )").find(line)?.value ?: return new
    if (line.length == marker.length) {
        // Empty item: remove the marker and leave the list.
        val text = new.text.removeRange(lineStart, cursor - 1)
        return TextFieldValue(text, TextRange(lineStart + 1))
    }
    val next = NUMBERED.find(marker)?.let { "${it.groupValues[1].toInt() + 1}. " } ?: marker
    val text = new.text.substring(0, cursor) + next + new.text.substring(cursor)
    return TextFieldValue(text, TextRange(cursor + next.length))
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


// ----- toolbar state -----

/** Which formats apply at the cursor or selection: "**", "*", "~~" and the line markers "# ", "## ", "- ", "> ", "1. ". */
fun activeFormats(value: TextFieldValue): Set<String> {
    val t = value.text
    val a = value.selection.min.coerceIn(0, t.length)
    val b = value.selection.max.coerceIn(0, t.length)
    val result = HashSet<String>()
    BOLD.findAll(t).forEach { if (a >= it.range.first + 2 && b <= it.range.last - 1) result.add("**") }
    STRIKE.findAll(t).forEach { if (a >= it.range.first + 2 && b <= it.range.last - 1) result.add("~~") }
    ITALIC.findAll(t).forEach { if (a >= it.range.first + 1 && b <= it.range.last) result.add("*") }
    val rest = t.substring(lineStartOf(t, a))
    when {
        rest.startsWith("## ") -> result.add("## ")
        rest.startsWith("# ") -> result.add("# ")
        rest.startsWith("- ") -> result.add("- ")
        rest.startsWith("> ") -> result.add("> ")
        NUMBERED.containsMatchIn(rest) -> result.add("1. ")
    }
    return result
}

/**
 * A format button pressed with no text selected switches the format on (or off) for the next thing typed,
 * instead of putting symbols in the note. This wraps that typed text.
 */
fun applyPending(old: TextFieldValue, new: TextFieldValue, on: Set<String>, off: Set<String>): TextFieldValue? {
    if (on.isEmpty() && off.isEmpty()) return null
    if (!old.selection.collapsed || !new.selection.collapsed) return null
    val c = old.selection.start
    val grew = new.text.length - old.text.length
    if (grew <= 0 || c > old.text.length || new.selection.start != c + grew) return null
    val inserted = new.text.substring(c, c + grew)
    if (new.text != old.text.substring(0, c) + inserted + old.text.substring(c)) return null
    var pos = c
    var pre = ""
    var post = ""
    for (m in off) {
        if (old.text.startsWith(m, pos)) pos += m.length else { pre += m; post = m + post }
    }
    val opens = on.joinToString("")
    val closes = on.reversed().joinToString("")
    val text = old.text.substring(0, pos) + pre + opens + inserted + closes + post + old.text.substring(pos)
    return TextFieldValue(text, TextRange(pos + pre.length + opens.length + inserted.length))
}
