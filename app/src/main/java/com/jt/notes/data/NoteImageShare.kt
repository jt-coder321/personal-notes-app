package com.jt.notes.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/**
 * Renders a note's title and plain body to a PNG and shares it, following
 * the StaticLayout pattern in NoteExport.writePdf. Long notes render fully
 * up to a 6000 px tall image; beyond that the text is cut and an ellipsis
 * line marks the crop. The PNG goes through the existing FileProvider
 * shared folder, like other shared files.
 */
object NoteImageShare {
    private const val WIDTH_PX = 1080
    private const val PADDING_PX = 72
    private const val MAX_HEIGHT_PX = 6000

    /** Renders the note to a PNG in the cache shared folder. Call off the main thread. */
    fun renderToFile(context: Context, note: Note): File {
        val bitmap = render(note)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "note-${note.id}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Shares a rendered PNG with the FileProvider grant, mirroring shareAsText. */
    fun shareFile(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "com.jt.notes.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share note as image"))
    }

    private fun render(note: Note): Bitmap {
        val body = if (note.isChecklist) checklistBodyToText(note.body) else NoteExport.plainFromMarkup(note.body)
        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 64f
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint().apply {
            isAntiAlias = true
            color = Color.parseColor("#222222")
            textSize = 44f
        }
        val contentWidth = WIDTH_PX - 2 * PADDING_PX
        val titleText = note.title.trim()
        val titleLayout = titleText.takeIf { it.isNotEmpty() }?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1f)
                .build()
        }
        val bodyText = body.ifEmpty { " " }
        val bodyLayout = StaticLayout.Builder.obtain(bodyText, 0, bodyText.length, bodyPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1f)
            .build()
        var height = PADDING_PX * 2 + (titleLayout?.height ?: 0) + (if (titleLayout != null) 32 else 0) + bodyLayout.height
        val truncated = height > MAX_HEIGHT_PX
        if (truncated) height = MAX_HEIGHT_PX
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = PADDING_PX.toFloat()
        titleLayout?.let {
            canvas.save()
            canvas.translate(PADDING_PX.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + 32
        }
        canvas.save()
        canvas.translate(PADDING_PX.toFloat(), y)
        bodyLayout.draw(canvas)
        canvas.restore()
        if (truncated) {
            // A white strip with an ellipsis over the cut edge, so the crop is visible.
            canvas.drawRect(0f, (height - 80).toFloat(), WIDTH_PX.toFloat(), height.toFloat(), Paint().apply { color = Color.WHITE })
            val dotPaint = Paint().apply { isAntiAlias = true; color = Color.GRAY; textSize = 44f }
            canvas.drawText("…", PADDING_PX.toFloat(), (height - 24).toFloat(), dotPaint)
        }
        return bitmap
    }
}
