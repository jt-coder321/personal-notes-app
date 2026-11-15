package com.jt.notes.data

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import java.io.OutputStream

/** Turns one note into Markdown text or a PDF file. */
object NoteExport {

    fun toMarkdown(note: Note): String = buildString {
        if (note.title.isNotBlank()) append("# ").append(note.title.trim()).append("\n\n")
        if (note.isChecklist) {
            parseItems(note.body).filter { it.text.isNotBlank() }.forEach {
                if (it.indent > 0) append("  ")
                append(if (it.checked) "- [x] " else "- [ ] ").append(it.text).append('\n')
            }
        } else {
            append(note.body)
        }
    }.trimEnd() + "\n"

    /** Writes an A4 PDF of the note's text. Pictures are not included. Does not close [out]. */
    fun writePdf(note: Note, out: OutputStream) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48
        val contentWidth = pageWidth - 2 * margin
        val contentHeight = pageHeight - 2 * margin

        val text = SpannableStringBuilder()
        if (note.title.isNotBlank()) {
            val start = text.length
            text.append(note.title.trim())
            text.setSpan(RelativeSizeSpan(1.7f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n\n")
        }
        if (note.isChecklist) {
            parseItems(note.body).filter { it.text.isNotBlank() }.forEach {
                if (it.indent > 0) text.append("      ")
                text.append(if (it.checked) "[x]  " else "[  ]  ").append(it.text).append('\n')
            }
        } else {
            text.append(plainFromMarkup(note.body))
        }
        if (text.isEmpty()) text.append(" ")

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = 12f
            color = 0xFF111111.toInt()
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1f)
            .build()

        val pdf = PdfDocument()
        var line = 0
        var pageNumber = 1
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= contentHeight) last++
            val bottom = layout.getLineBottom(last)

            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val canvas = page.canvas
            canvas.save()
            canvas.translate(margin.toFloat(), (margin - top).toFloat())
            canvas.clipRect(0, top, contentWidth, bottom)
            layout.draw(canvas)
            canvas.restore()
            pdf.finishPage(page)

            line = last + 1
            pageNumber++
        }
        pdf.writeTo(out)
        pdf.close()
    }

    /** Drops the simple formatting marks so exported text reads cleanly. */
    private fun plainFromMarkup(body: String): String =
        body.split("\n").joinToString("\n") { line ->
            val stripped = when {
                line.startsWith("## ") -> line.substring(3)
                line.startsWith("# ") -> line.substring(2)
                line.startsWith("- ") -> "• " + line.substring(2)
                else -> line
            }
            stripped.replace("**", "")
        }
}
