package com.jt.notes.data

import androidx.room.withTransaction
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Imports notes from an Evernote .enex export: titles, content XHTML
 * reduced to plain text, created dates, and tags as labels. Resources
 * (pictures and files) are skipped. An .enex file carries no stable note
 * id, so importing the same file twice creates duplicates.
 */
object EvernoteImport {
    data class Outcome(val added: Int)

    private val CREATED = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun import(db: NotesDb, input: InputStream): Outcome {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val dao = db.dao()
        var added = 0
        db.withTransaction {
            var event = parser.eventType
            var title: String? = null
            var content: String? = null
            var created: Long? = null
            val tags = ArrayList<String>()
            var inNote = false
            var inResource = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "note" -> {
                            inNote = true
                            title = null
                            content = null
                            created = null
                            tags.clear()
                        }
                        "resource" -> inResource = true
                        "title" -> if (inNote && !inResource) title = readElementText(parser)
                        "content" -> if (inNote && !inResource) content = readElementText(parser)
                        "created" -> if (inNote && !inResource) {
                            created = runCatching { CREATED.parse(readElementText(parser))?.time }.getOrNull()
                        }
                        "tag" -> if (inNote && !inResource) {
                            readElementText(parser).trim().takeIf { it.isNotEmpty() }?.let { tags.add(it) }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "resource" -> inResource = false
                        "note" -> if (inNote) {
                            inNote = false
                            val now = System.currentTimeMillis()
                            val id = dao.insertNote(
                                Note(
                                    uuid = "enex-" + UUID.randomUUID().toString(),
                                    title = title.orEmpty(),
                                    body = xhtmlToText(content.orEmpty()),
                                    createdAt = created ?: now,
                                    updatedAt = created ?: now,
                                ),
                            )
                            tags.forEach { dao.addNoteLabel(NoteLabel(id, Backup.ensureLabel(dao, it))) }
                            added++
                        }
                    }
                }
                event = parser.next()
            }
        }
        require(added > 0) { "No Evernote notes were found in that file" }
        return Outcome(added)
    }

    /** Collects all text inside the element the parser sits on, nested tags included. */
    private fun readElementText(parser: XmlPullParser): String {
        val out = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return out.toString()
            }
        }
        return out.toString()
    }

    /** XHTML to plain text: block breaks become newlines, tags are dropped, entities unescaped. */
    internal fun xhtmlToText(xhtml: String): String {
        var text = xhtml
        text = Regex("(?i)<(br|/div|/p|/li|/tr|/h[1-6])[^>]*>").replace(text, "\n")
        text = Regex("(?i)<li[^>]*>").replace(text, "\n- ")
        text = Regex("<[^>]+>").replace(text, "")
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .lineSequence().joinToString("\n") { it.trimEnd() }
            .trim()
    }
}
