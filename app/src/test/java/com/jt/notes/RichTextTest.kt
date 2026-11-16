package com.jt.notes

import androidx.compose.ui.text.TextRange
import com.jt.notes.ui.Fmt
import com.jt.notes.ui.Rich
import com.jt.notes.ui.Span
import com.jt.notes.ui.isHeadingOn
import com.jt.notes.ui.isInlineOn
import com.jt.notes.ui.lineMarker
import com.jt.notes.ui.richEdit
import com.jt.notes.ui.richFromMarkdown
import com.jt.notes.ui.richToMarkdown
import com.jt.notes.ui.toggleHeading
import com.jt.notes.ui.toggleInline
import com.jt.notes.ui.togglePrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays the keyboard and the toolbar against the editor engine, with no phone needed. */
class RichTextTest {
    private fun start(text: String = "", cursor: Int = text.length) = Rich(text, emptyList(), TextRange(cursor))

    private fun Rich.type(s: String): Rich {
        var r = this
        for (ch in s) {
            val sel = r.selection
            val t = r.text.substring(0, sel.min) + ch + r.text.substring(sel.max)
            r = richEdit(r, t, TextRange(sel.min + 1))
        }
        return r
    }

    private fun Rich.backspace(): Rich {
        val k = selection.min
        return richEdit(this, text.removeRange(k - 1, k), TextRange(k - 1))
    }

    private fun Rich.move(pos: Int) = richEdit(this, text, TextRange(pos))
    private fun Rich.bold() = toggleInline(this, Fmt.BOLD)
    private fun Rich.italic() = toggleInline(this, Fmt.ITALIC)
    private fun Rich.strike() = toggleInline(this, Fmt.STRIKE)
    private val Rich.md get() = richToMarkdown(this)

    @Test fun boldTyped() {
        val r = start().bold().type("bold text")
        assertEquals("bold text", r.text)
        assertEquals("**bold text**", r.md)
        assertTrue(isInlineOn(r, Fmt.BOLD))
    }

    @Test fun enterKeepsFormattingAndNothingIsShownRaw() {
        val r = start().bold().type("bold text").type("\n")
        assertEquals("bold text\n", r.text)
        assertEquals("**bold text**\n", r.md)
        assertTrue(isInlineOn(r, Fmt.BOLD))
        assertEquals("**bold text**\n**next**", r.type("next").md)
        assertEquals("bold text\nnext", r.type("next").text)
    }

    @Test fun switchOffThenPlain() {
        val r = start().bold().type("bold").bold().type(" plain")
        assertEquals("**bold** plain", r.md)
        assertFalse(isInlineOn(r, Fmt.BOLD))
    }

    @Test fun switchOffInTheMiddle() {
        val inside = richFromMarkdown("**ab**").move(1).bold().type("X")
        assertEquals("**a**X**b**", inside.md)
    }

    @Test fun comboBoldItalicStrike() {
        val r = start().bold().italic().type("both")
        assertEquals("both", r.text)
        assertEquals("***both***", r.md)
        val all = start().bold().italic().strike().type("all").md
        assertEquals("all", richFromMarkdown(all).text)
    }

    @Test fun comboSurvivesEnterAndKeepsGoing() {
        val r = start().bold().italic().type("one").type("\n").type("two")
        assertEquals("one\ntwo", r.text)
        assertEquals("***one***\n***two***", r.md)
    }

    @Test fun mixedStylesOnOneLine() {
        val r = start().type("a ").bold().type("b").bold().type(" c ").italic().type("d").italic().type(" e ").strike().type("f")
        assertEquals("a b c d e f", r.text)
        assertEquals("a **b** c *d* e ~~f~~", r.md)
        val back = richFromMarkdown(r.md)
        assertEquals(r.text, back.text)
        assertEquals(r.spans.toSet(), back.spans.toSet())
    }

    @Test fun selectionFormatting() {
        val r = start("hello world", 11).let { richEdit(it, it.text, TextRange(6, 11)) }
        val b = toggleInline(r, Fmt.BOLD)
        assertEquals("hello **world**", b.md)
        assertTrue(isInlineOn(b, Fmt.BOLD))
        val i = toggleInline(b, Fmt.ITALIC)
        assertEquals("hello ***world***", i.md)
        val off = toggleInline(i, Fmt.BOLD)
        assertEquals("hello *world*", off.md)
    }

    @Test fun typingInsideBoldStaysBold() {
        val r = richFromMarkdown("**ab**").move(1).type("X")
        assertEquals("aXb", r.text)
        assertEquals("**aXb**", r.md)
        val atStart = richFromMarkdown("**ab**").move(0).type("X")
        assertEquals("X**ab**", atStart.md)
        val atEnd = richFromMarkdown("**ab**").move(2).type("X")
        assertEquals("**abX**", atEnd.md)
    }

    @Test fun backspaceKeepsStylesTidy() {
        val r = richFromMarkdown("**ab** c").move(2).backspace()
        assertEquals("**a** c", r.md)
        val gone = richFromMarkdown("**a**").move(1).backspace()
        assertEquals("", gone.md)
        assertEquals(emptyList<Span>(), gone.spans)
    }

    @Test fun bulletsContinueAndEnd() {
        val r = start().let { togglePrefix(it, "• ") }.type("one\n").type("two\n")
        assertEquals("• one\n• two\n• ", r.text)
        assertEquals("- one\n- two\n- ", r.md)
        val ended = r.type("\n")
        assertEquals("• one\n• two\n", ended.text)
        assertEquals(ended.text.length, ended.selection.start)
    }

    @Test fun bulletWithBoldContinues() {
        val r = togglePrefix(start(), "• ").bold().type("b").type("\n")
        assertEquals("• b\n• ", r.text)
        assertEquals("- **b**\n- ", r.md)
        assertTrue(isInlineOn(r, Fmt.BOLD))
    }

    @Test fun numbersCountUp() {
        val r = togglePrefix(start(), "1. ").type("a\n").type("b\n")
        assertEquals("1. a\n2. b\n3. ", r.text)
    }

    @Test fun quoteContinues() {
        val r = togglePrefix(start(), "▎ ").type("q\n")
        assertEquals("▎ q\n▎ ", r.text)
        assertEquals("> q\n> ", r.md)
    }

    @Test fun backspaceRemovesWholeMarker() {
        val r = togglePrefix(start(), "• ").type("x").move(2).backspace()
        assertEquals("x", r.text)
        assertEquals("", lineMarker(r) ?: "")
    }

    @Test fun markerToggles() {
        val bullet = togglePrefix(start("hello"), "• ")
        assertEquals("• hello", bullet.text)
        assertEquals("1. hello", togglePrefix(bullet, "1. ").text)
        assertEquals("hello", togglePrefix(togglePrefix(bullet, "1. "), "1. ").text)
    }

    @Test fun headings() {
        val r = start("Title\nbody", 2)
        val h = toggleHeading(r, Fmt.H1)
        assertTrue(isHeadingOn(h, Fmt.H1))
        assertEquals("# Title\nbody", h.md)
        val h2 = toggleHeading(h, Fmt.H2)
        assertEquals("## Title\nbody", h2.md)
        assertFalse(isHeadingOn(h2, Fmt.H1))
        assertEquals("Title\nbody", toggleHeading(h2, Fmt.H2).md)
        val typed = toggleHeading(start("Hi"), Fmt.H1).type("!")
        assertEquals("# Hi!", typed.md)
        val enter = typed.type("\n").type("x")
        assertEquals("# Hi!\nx", enter.md)
    }

    @Test fun headingWithBoldWords() {
        val r = start().type("A ").bold().type("big").bold().let { toggleHeading(it, Fmt.H1) }
        assertEquals("# A **big**", r.md)
    }

    @Test fun markdownRoundTrips() {
        val samples = listOf(
            "plain text",
            "# Title\n\nsome **bold** and *italic* and ~~gone~~",
            "- one\n- two\n1. a\n2. b\n> quote",
            "***both*** and **bold *nested italic* end**",
            "2 \\* 3 \\* 4 and a \\~ tilde",
            "",
        )
        for (s in samples) assertEquals(s, richToMarkdown(richFromMarkdown(s)))
        assertEquals("2 * 3 * 4", richFromMarkdown("2 \\* 3 \\* 4").text)
    }

    @Test fun typedStarsStayLiteral() {
        val r = start().type("2 * 3 * 4")
        assertEquals("2 \\* 3 \\* 4", r.md)
        assertEquals("2 * 3 * 4", richFromMarkdown(r.md).text)
    }

    @Test fun oldMarkdownNotesOpenClean() {
        val r = richFromMarkdown("## Sub\n- item with **bold**\n> quote")
        assertEquals("Sub\n• item with bold\n▎ quote", r.text)
    }

    @Test fun selectionOnlyChangeClearsPending() {
        val r = start("abc").bold().move(1)
        assertTrue(r.on.isEmpty() && r.off.isEmpty())
    }

    @Test fun replacingSelectedWordKeepsStyle() {
        val r = richFromMarkdown("say **hello** now")
        val replaced = richEdit(r.copy(selection = TextRange(4, 9)), "say X now", TextRange(5))
        assertEquals("say **X** now", replaced.md)
    }
}
