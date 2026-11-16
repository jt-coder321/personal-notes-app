package com.jt.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.jt.notes.ui.EditState
import com.jt.notes.ui.MarkupTransformation
import com.jt.notes.ui.activeFormats
import com.jt.notes.ui.displayMarkup
import com.jt.notes.ui.isFormatOn
import com.jt.notes.ui.processEdit
import com.jt.notes.ui.toggleLinePrefix
import com.jt.notes.ui.toggleMarkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pretends to be the keyboard and the toolbar, so the formatting rules can be checked without a phone. */
class MarkupTest {
    private fun start(text: String = "", cursor: Int = text.length) =
        EditState(TextFieldValue(text, TextRange(cursor)))

    private fun EditState.type(s: String): EditState {
        var st = this
        for (ch in s) {
            val v = st.value
            val t = v.text.substring(0, v.selection.min) + ch + v.text.substring(v.selection.max)
            st = processEdit(st, TextFieldValue(t, TextRange(v.selection.min + 1)))
        }
        return st
    }

    private fun EditState.backspace(): EditState {
        val v = value
        val k = v.selection.min
        return processEdit(this, TextFieldValue(v.text.removeRange(k - 1, k), TextRange(k - 1)))
    }

    private fun EditState.line(prefix: String) = copy(value = toggleLinePrefix(value, prefix))
    private fun EditState.mark(m: String) = toggleMarkState(this, m)
    private val EditState.text get() = value.text

    @Test fun boldTypedThenEnterStaysFormatted() {
        val s = start().mark("**").type("bolded text")
        assertEquals("**bolded text**", s.text)
        val e = s.type("\n")
        assertEquals("**bolded text**\n", e.text)
        assertTrue(isFormatOn(e, "**"))
        assertEquals("**bolded text**\n**next**", e.type("next").text)
    }

    @Test fun boldCanBeSwitchedOffAndOn() {
        val s = start().mark("**").type("bold").mark("**").type(" plain")
        assertEquals("**bold** plain", s.text)
        assertEquals("**bold** plain **again**", s.mark("**").type(" ").mark("**").type("again").text.replace("plain  ", "plain "))
    }

    @Test fun boldSwitchedOffInTheMiddleSplitsTheRun() {
        val s = start("**ab**", 3).mark("**").type("X")
        assertEquals("**a**X**b**", s.text)
    }

    @Test fun enterInTheMiddleOfBoldSplitsTheLine() {
        val s = start("**abcd**", 4).type("\n")
        assertEquals("**ab**\n**cd**", s.text)
    }

    @Test fun italicAndStrikethrough() {
        assertEquals("*it*", start().mark("*").type("it").text)
        assertEquals("~~gone~~", start().mark("~~").type("gone").text)
        assertEquals("~~gone~~\n", start().mark("~~").type("gone").type("\n").text)
    }

    @Test fun enterWithFormatOnButNothingTypedKeepsNoStrayMarks() {
        val s = start("hello").mark("**").type("\n").type("x")
        assertEquals("hello\n**x**", s.text)
    }

    @Test fun selectionBoldToggles() {
        val v = TextFieldValue("hello world", TextRange(6, 11))
        val on = toggleMarkState(EditState(v), "**")
        assertEquals("hello **world**", on.value.text)
        assertTrue(isFormatOn(on, "**"))
        val off = toggleMarkState(on, "**")
        assertEquals("hello world", off.value.text)
    }

    @Test fun bulletsContinueAndEnd() {
        val s = start().line("- ").type("one\n").type("two\n")
        assertEquals("- one\n- two\n- ", s.text)
        assertEquals("- one\n- two\n\n", s.type("\n").text)
    }

    @Test fun bulletWithBoldContinues() {
        val s = start().line("- ").mark("**").type("b").type("\n")
        assertEquals("- **b**\n- ", s.text)
    }

    @Test fun numbersCountUp() {
        val s = start().line("1. ").type("a\n").type("b\n")
        assertEquals("1. a\n2. b\n3. ", s.text)
    }

    @Test fun quoteContinues() {
        assertEquals("> q\n> ", start().line("> ").type("q\n").text)
    }

    @Test fun headingsAndBackspace() {
        val s = start().line("# ").type("Title")
        assertEquals("# Title", s.text)
        assertTrue(isFormatOn(s, "# "))
        val atStart = EditState(TextFieldValue("# T", TextRange(2))).backspace()
        assertEquals("T", atStart.text)
        assertEquals("## Sub", start().line("## ").type("Sub").text)
    }

    @Test fun backspaceThroughBold() {
        assertEquals("**a**", start("**ab**", 4).backspace().text)
        assertEquals("**a**", start("**ab**", 6).backspace().text)
        assertEquals("a", start("**a**", 3).backspace().text.let { it.replace("*", "") })
    }

    @Test fun symbolsAreHiddenInTheEditor() {
        val shown = MarkupTransformation(Color.Gray).filter(AnnotatedString("**a** b ~~c~~ *d*\n# H\n## S\n- x\n> q\n1. n"))
        assertEquals("a b c d\nH\nS\n\u2022 x\n\u258E q\n1. n", shown.text.text)
    }

    @Test fun cursorMapping() {
        val t = "**ab** x\n# H"
        val m = MarkupTransformation(Color.Gray).filter(AnnotatedString(t)).offsetMapping
        assertEquals(0, m.originalToTransformed(0))
        assertEquals(2, m.originalToTransformed(4))
        assertEquals(4, m.transformedToOriginal(2)) // end of bold text stays inside the bold
        assertEquals(t.length - 1, m.transformedToOriginal(m.originalToTransformed(t.length - 1)))
        assertEquals(11, m.transformedToOriginal(m.originalToTransformed(11))) // heading text
        var last = -1
        for (i in 0..t.length) {
            val x = m.originalToTransformed(i)
            assertTrue(x >= last)
            last = x
        }
    }

    @Test fun activeFormatsFollowTheCursor() {
        val v = TextFieldValue("a **b** c", TextRange(5))
        assertTrue("**" in activeFormats(v))
        assertTrue("**" !in activeFormats(TextFieldValue("a **b** c", TextRange(0))))
        assertTrue("- " in activeFormats(TextFieldValue("- x", TextRange(3))))
    }

    @Test fun readingViewHasNoSymbols() {
        assertEquals("b i s\n\u2022 x\n\u258E q", displayMarkup("**b** *i* ~~s~~\n- x\n> q").text)
    }
}
