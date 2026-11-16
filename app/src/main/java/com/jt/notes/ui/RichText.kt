package com.jt.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/*
 * Rich text for the note editor. The editor holds plain text plus a list of style ranges, so the
 * screen never contains formatting symbols. When a note is saved the styles are written as markdown
 * (**bold**, *italic*, ~~strike~~, __underline__, `code`, # heading), and read back when the note
 * is opened.
 */

/** Order matters: it is the nesting order when written as markdown. */
enum class Fmt { BOLD, STRIKE, ITALIC, H1, H2, UNDERLINE, CODE }

data class Span(val fmt: Fmt, val start: Int, val end: Int)

data class Rich(
    val text: String = "",
    val spans: List<Span> = emptyList(),
    val selection: TextRange = TextRange(0),
    /** Styles switched on or off for the next typed text. */
    val on: Set<Fmt> = emptySet(),
    val off: Set<Fmt> = emptySet(),
)

private val INLINE_FMTS = listOf(Fmt.BOLD, Fmt.STRIKE, Fmt.ITALIC, Fmt.UNDERLINE, Fmt.CODE)
private val MARKER = Regex("^(• |▎ |\\d+\\. )")
private const val BULLET = "• "
private const val QUOTE = "▎ "

// ----- spans -----

/** Clips ranges to the text, splits them at line breaks and merges neighbours of the same style. */
fun normalize(text: String, spans: List<Span>): List<Span> {
    val out = ArrayList<Span>()
    for (fmt in Fmt.values()) {
        val pieces = ArrayList<Pair<Int, Int>>()
        for (sp in spans) {
            if (sp.fmt != fmt) continue
            val a = sp.start.coerceIn(0, text.length)
            val b = sp.end.coerceIn(0, text.length)
            var segStart = -1
            for (i in a until b) {
                if (text[i] == '\n') {
                    if (segStart >= 0) { pieces.add(segStart to i); segStart = -1 }
                } else if (segStart < 0) {
                    segStart = i
                }
            }
            if (segStart >= 0) pieces.add(segStart to b)
        }
        pieces.sortBy { it.first }
        var cur: Pair<Int, Int>? = null
        for (p in pieces) {
            val c = cur
            if (c == null) cur = p
            else if (p.first <= c.second) cur = c.first to maxOf(c.second, p.second)
            else { out.add(Span(fmt, c.first, c.second)); cur = p }
        }
        cur?.let { out.add(Span(fmt, it.first, it.second)) }
    }
    return out
}

private fun covers(spans: List<Span>, fmt: Fmt, a: Int, b: Int) =
    spans.any { it.fmt == fmt && it.start <= a && it.end >= b }

private fun setCoverage(spans: List<Span>, fmt: Fmt, a: Int, b: Int, want: Boolean): List<Span> {
    if (a >= b) return spans
    val rest = ArrayList<Span>()
    for (sp in spans) {
        if (sp.fmt != fmt || sp.end <= a || sp.start >= b) {
            rest.add(sp)
        } else {
            if (sp.start < a) rest.add(Span(fmt, sp.start, a))
            if (sp.end > b) rest.add(Span(fmt, b, sp.end))
        }
    }
    if (want) rest.add(Span(fmt, a, b))
    return rest
}

private fun lineStart(text: String, pos: Int): Int = text.lastIndexOf('\n', pos - 1) + 1

private fun lineEnd(text: String, pos: Int): Int = text.indexOf('\n', pos).let { if (it < 0) text.length else it }

/** Styles that the character just before the cursor has, so typing at the end of bold text continues it. */
private fun styleBefore(r: Rich, pos: Int): Set<Fmt> =
    if (pos > 0 && pos <= r.text.length && r.text[pos - 1] != '\n') {
        r.spans.filter { it.start <= pos - 1 && it.end >= pos }.map { it.fmt }.toSet()
    } else {
        emptySet()
    }

// ----- editing -----

private fun locate(old: Rich, newText: String, newSel: TextRange): Triple<Int, Int, String> {
    val o = old.text
    val c = old.selection
    if (c.collapsed && newSel.collapsed) {
        val grew = newText.length - o.length
        val cs = c.start
        if (grew > 0 && cs <= o.length && newSel.start == cs + grew &&
            newText == o.substring(0, cs) + newText.substring(cs, cs + grew) + o.substring(cs)
        ) return Triple(cs, 0, newText.substring(cs, cs + grew))
        if (grew < 0 && newSel.start >= 0 && newSel.start == cs + grew && newText == o.removeRange(newSel.start, cs)) {
            return Triple(newSel.start, -grew, "")
        }
    }
    var p = 0
    val max = minOf(o.length, newText.length)
    while (p < max && o[p] == newText[p]) p++
    var s = 0
    while (s < max - p && o[o.length - 1 - s] == newText[newText.length - 1 - s]) s++
    return Triple(p, o.length - p - s, newText.substring(p, newText.length - s))
}

/** Replaces [p, p+r) with ins, moving the style ranges along. `want` is the style of the new text. */
private fun applyChange(old: Rich, p: Int, r: Int, ins: String, newSel: TextRange, want: Set<Fmt>?): Rich {
    val m = ins.length
    val newText = old.text.substring(0, p) + ins + old.text.substring(p + r)
    fun mapStart(pos: Int) =
        if (r == 0) { if (pos >= p) pos + m else pos }
        else if (pos <= p) pos else if (pos >= p + r) pos - r + m else p
    fun mapEnd(pos: Int) =
        if (r == 0) { if (pos > p) pos + m else pos }
        else if (pos >= p + r) pos - r + m else if (pos <= p) pos else p
    var spans = old.spans.map { Span(it.fmt, mapStart(it.start), mapEnd(it.end)) }.filter { it.end > it.start }
    var on = old.on
    var off = old.off
    if (m > 0) {
        val explicit = on.isNotEmpty() || off.isNotEmpty()
        // Typing over selected text takes the style of the text it replaces.
        val replaced = if (r > 0) old.spans.filter { it.start <= p && it.end > p }.map { it.fmt }.toSet() else null
        val base = (replaced ?: styleBefore(old, p)) + old.spans.filter { (it.fmt == Fmt.H1 || it.fmt == Fmt.H2) && it.start == p }.map { it.fmt }
        val styled = want ?: if (explicit) (base + on) - off else base
        for (f in Fmt.values()) spans = setCoverage(spans, f, p, p + m, f in styled)
        if (want == null) {
            if (ins.contains('\n')) { on = styled.filter { it in INLINE_FMTS }.toSet(); off = emptySet() }
            else { on = emptySet(); off = emptySet() }
        }
    }
    return Rich(newText, normalize(newText, spans), newSel, on, off)
}

/** Handles one edit from the keyboard: new text and cursor in, new state out. */
fun richEdit(old: Rich, newText: String, newSel: TextRange): Rich {
    if (newText == old.text) {
        val moved = newSel != old.selection
        return old.copy(selection = newSel, on = if (moved) emptySet() else old.on, off = if (moved) emptySet() else old.off)
    }
    val (p, r, ins) = locate(old, newText, newSel)
    // Backspace on a bullet, number or quote marker removes the whole marker.
    if (ins.isEmpty() && r == 1) {
        val ls = lineStart(old.text, p)
        val marker = MARKER.find(old.text.substring(ls))?.value
        if (marker != null && p < ls + marker.length) {
            return applyChange(old, ls, marker.length, "", TextRange(ls), emptySet()).copy(on = old.on, off = old.off)
        }
    }
    val res = applyChange(old, p, r, ins, newSel, null)
    // Enter at the end of a list item starts the next item; Enter on an empty item ends the list.
    if (ins == "\n" && r == 0 && newSel.collapsed && newSel.start == p + 1) {
        val ls = lineStart(res.text, p)
        val line = res.text.substring(ls, p)
        val marker = MARKER.find(line)?.value
        if (marker != null) {
            if (line.length == marker.length) {
                return applyChange(res, ls, p + 1 - ls, "", TextRange(ls), emptySet()).copy(on = res.on, off = res.off)
            }
            val next = Regex("^(\\d+)\\. ").find(marker)?.let { "${it.groupValues[1].toInt() + 1}. " } ?: marker
            return applyChange(res, p + 1, 0, next, TextRange(p + 1 + next.length), emptySet()).copy(on = res.on, off = res.off)
        }
    }
    return res
}

// ----- toolbar -----

fun isInlineOn(r: Rich, fmt: Fmt): Boolean {
    val sel = r.selection
    if (!sel.collapsed) return covers(r.spans, fmt, sel.min, sel.max)
    return fmt !in r.off && (fmt in styleBefore(r, sel.start) || fmt in r.on)
}

fun toggleInline(r: Rich, fmt: Fmt): Rich {
    val sel = r.selection
    if (!sel.collapsed) {
        val whole = covers(r.spans, fmt, sel.min, sel.max)
        return r.copy(spans = normalize(r.text, setCoverage(r.spans, fmt, sel.min, sel.max, !whole)))
    }
    return if (fmt in styleBefore(r, sel.start)) {
        r.copy(on = r.on - fmt, off = if (fmt in r.off) r.off - fmt else r.off + fmt)
    } else {
        r.copy(on = if (fmt in r.on) r.on - fmt else r.on + fmt)
    }
}

private fun selectedLines(r: Rich): List<Pair<Int, Int>> {
    val a = lineStart(r.text, r.selection.min.coerceIn(0, r.text.length))
    val bPos = r.selection.max.coerceIn(0, r.text.length)
    val lines = ArrayList<Pair<Int, Int>>()
    var ls = a
    while (true) {
        val le = lineEnd(r.text, ls)
        lines.add(ls to le)
        if (le >= bPos || le >= r.text.length) break
        ls = le + 1
    }
    return lines
}

fun isHeadingOn(r: Rich, fmt: Fmt): Boolean {
    val lines = selectedLines(r).filter { it.second > it.first }
    return lines.isNotEmpty() && lines.all { covers(r.spans, fmt, it.first, it.second) }
}

/** Heading buttons style the whole line (or every selected line). */
fun toggleHeading(r: Rich, fmt: Fmt): Rich {
    val lines = selectedLines(r).filter { it.second > it.first }
    if (lines.isEmpty()) return r
    val remove = isHeadingOn(r, fmt)
    var spans = r.spans
    val other = if (fmt == Fmt.H1) Fmt.H2 else Fmt.H1
    for ((a, b) in lines) {
        spans = setCoverage(spans, other, a, b, false)
        spans = setCoverage(spans, fmt, a, b, !remove)
    }
    return r.copy(spans = normalize(r.text, spans))
}

/** The list or quote marker on the cursor's line: "• ", "1. " or "▎ ", or null. */
fun lineMarker(r: Rich): String? {
    val pos = r.selection.min.coerceIn(0, r.text.length)
    val marker = MARKER.find(r.text.substring(lineStart(r.text, pos)))?.value ?: return null
    return if (marker[0].isDigit()) "1. " else marker
}

/** Adds, changes or removes a bullet, number or quote marker at the start of the cursor's line. */
fun togglePrefix(r: Rich, kind: String): Rich {
    val pos = r.selection.min.coerceIn(0, r.text.length)
    val ls = lineStart(r.text, pos)
    val current = MARKER.find(r.text.substring(ls))?.value
    val currentKind = current?.let { if (it[0].isDigit()) "1. " else it }
    val removing = currentKind == kind
    val newMarker = if (removing) "" else kind
    val delta = newMarker.length - (current?.length ?: 0)
    val caret = maxOf(ls + newMarker.length, r.selection.start + delta)
    return applyChange(r, ls, current?.length ?: 0, newMarker, TextRange(caret), emptySet()).copy(on = r.on, off = r.off)
}

const val BULLET_MARKER = BULLET
const val QUOTE_MARKER = QUOTE
const val NUMBER_MARKER = "1. "

// ----- markdown in and out -----

private val INLINE = Regex("\\*\\*\\*(.+?)\\*\\*\\*|\\*\\*(.+?)\\*\\*|~~(.+?)~~|(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)|__(.+?)__|`([^`\\n]+?)`")

private fun parseInline(s: String, sb: StringBuilder, spans: MutableList<Span>) {
    var cursor = 0
    for (m in INLINE.findAll(s)) {
        sb.append(s, cursor, m.range.first)
        val start = sb.length
        if (m.groups[6] != null) {
            // Code spans keep their contents literal: no nested styles inside backticks.
            sb.append(m.groupValues[6])
            spans.add(Span(Fmt.CODE, start, sb.length))
            cursor = m.range.last + 1
            continue
        }
        val fmts: List<Fmt>
        val inner: String
        when {
            m.groups[1] != null -> { inner = m.groupValues[1]; fmts = listOf(Fmt.BOLD, Fmt.ITALIC) }
            m.groups[2] != null -> { inner = m.groupValues[2]; fmts = listOf(Fmt.BOLD) }
            m.groups[3] != null -> { inner = m.groupValues[3]; fmts = listOf(Fmt.STRIKE) }
            m.groups[5] != null -> { inner = m.groupValues[5]; fmts = listOf(Fmt.UNDERLINE) }
            else -> { inner = m.groupValues[4]; fmts = listOf(Fmt.ITALIC) }
        }
        parseInline(inner, sb, spans)
        fmts.forEach { spans.add(Span(it, start, sb.length)) }
        cursor = m.range.last + 1
    }
    sb.append(s, cursor, s.length)
}

/** Opens a note: reads the saved markdown into plain text plus style ranges. */
fun richFromMarkdown(md: String): Rich {
    val sb = StringBuilder()
    val spans = ArrayList<Span>()
    md.split("\n").forEachIndexed { i, raw ->
        if (i > 0) sb.append('\n')
        val ls = sb.length
        var line = raw.replace("\\*", "").replace("\\~", "")
        var heading: Fmt? = null
        when {
            line.startsWith("## ") -> { heading = Fmt.H2; line = line.substring(3) }
            line.startsWith("# ") -> { heading = Fmt.H1; line = line.substring(2) }
            line.startsWith("- ") -> { sb.append(BULLET); line = line.substring(2) }
            line.startsWith("> ") -> { sb.append(QUOTE); line = line.substring(2) }
        }
        parseInline(line, sb, spans)
        if (heading != null && sb.length > ls) spans.add(Span(heading, ls, sb.length))
    }
    val text = sb.toString().replace('', '*').replace('', '~')
    return Rich(text, normalize(text, spans), TextRange(text.length))
}

private fun mark(f: Fmt) = when (f) { Fmt.BOLD -> "**"; Fmt.STRIKE -> "~~"; Fmt.ITALIC -> "*"; Fmt.UNDERLINE -> "__"; Fmt.CODE -> "`"; else -> "" }

/** Saves a note: writes the style ranges as markdown. */
fun richToMarkdown(r: Rich): String {
    val sb = StringBuilder()
    var ls = 0
    r.text.split("\n").forEachIndexed { i, line ->
        if (i > 0) sb.append('\n')
        val le = ls + line.length
        var cs = ls
        when {
            line.startsWith(BULLET) -> { sb.append("- "); cs = ls + 2 }
            line.startsWith(QUOTE) -> { sb.append("> "); cs = ls + 2 }
            line.isNotEmpty() && covers(r.spans, Fmt.H2, ls, ls + 1) -> sb.append("## ")
            line.isNotEmpty() && covers(r.spans, Fmt.H1, ls, ls + 1) -> sb.append("# ")
        }
        val cuts = sortedSetOf(cs, le)
        for (sp in r.spans) {
            if (sp.fmt !in INLINE_FMTS) continue
            if (sp.start in (cs + 1) until le) cuts.add(sp.start)
            if (sp.end in (cs + 1) until le) cuts.add(sp.end)
        }
        val points = cuts.toList()
        var stack = listOf<Fmt>()
        for (k in 0 until points.size - 1) {
            val a = points[k]
            val b = points[k + 1]
            val want = INLINE_FMTS.filter { f -> covers(r.spans, f, a, b) }
            var common = 0
            while (common < stack.size && common < want.size && stack[common] == want[common]) common++
            for (j in stack.size - 1 downTo common) sb.append(mark(stack[j]))
            for (j in common until want.size) sb.append(mark(want[j]))
            stack = want
            sb.append(r.text.substring(a, b).replace("*", "\\*").replace("~", "\\~"))
        }
        for (j in stack.size - 1 downTo 0) sb.append(mark(stack[j]))
        ls = le + 1
    }
    return sb.toString()
}

// ----- display -----

fun richAnnotated(r: Rich): AnnotatedString = buildAnnotatedString {
    append(r.text)
    for (sp in r.spans) {
        val style = when (sp.fmt) {
            Fmt.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            Fmt.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            Fmt.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            Fmt.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            Fmt.CODE -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color.Gray.copy(alpha = 0.22f),
            )
            Fmt.H1 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp)
            Fmt.H2 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
        }
        addStyle(style, sp.start.coerceIn(0, r.text.length), sp.end.coerceIn(0, r.text.length))
    }
}

