package com.jt.notes.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Whole-library export and import as plain .md files, one per note, so the
 * notes open in Obsidian and any other Markdown app. Each file is:
 *
 *   ---
 *   labels: work, ideas
 *   created: 2026-11-16T07:30:00
 *   updated: 2026-11-16T08:12:00
 *   ---
 *   <body as-is>
 *
 * Checklist bodies are written as-is (they are already Markdown task lists,
 * due markers included, so they round-trip). Pictures and voice notes are
 * not exported. Labels are comma-joined, so a label name containing a comma
 * splits into two labels on re-import. Label nesting is not preserved.
 */
object Vault {
    data class ExportOutcome(val written: Int, val imagesSkipped: Int)
    data class ImportOutcome(val added: Int, val failed: Int)

    private val DATE = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val WIKILINK = Regex("\\[\\[([^\\]|\\]]+)(?:\\|([^\\]]+))?\\]\\]")

    /** A safe file name from the title, with a uuid suffix when another note already took it. */
    internal fun fileNameFor(note: Note, used: MutableSet<String>): String {
        val base = note.title.trim().ifBlank { "Untitled" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim().take(80).ifBlank { "Untitled" }
        var name = "$base.md"
        if (name.lowercase() in used) {
            name = "$base-${note.uuid.take(8)}.md"
        }
        used.add(name.lowercase())
        return name
    }

    private fun toMarkdown(note: Note, labelNames: List<String>): String = buildString {
        append("---\n")
        if (labelNames.isNotEmpty()) append("labels: ").append(labelNames.joinToString(", ")).append('\n')
        append("created: ").append(DATE.format(Date(note.createdAt))).append('\n')
        append("updated: ").append(DATE.format(Date(note.updatedAt))).append('\n')
        append("---\n")
        append(note.body)
    }

    /** Writes every note except trash into the picked folder. Call from an IO thread. */
    suspend fun export(context: Context, db: NotesDb, folderUri: Uri): ExportOutcome {
        val dao = db.dao()
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: error("That folder could not be opened")
        if (!tree.canWrite()) error("That folder is not writable")
        val labels = dao.allLabels().associateBy { it.id }
        val links = dao.allNoteLabels().groupBy({ it.noteId }, { it.labelId })
        val live = dao.allNotes().filter { it.trashedAt == null }
        val liveIds = live.map { it.id }.toSet()
        val images = dao.allAttachments().count { it.noteId in liveIds }
        val used = HashSet<String>()
        var written = 0
        for (note in live) {
            val name = fileNameFor(note, used)
            tree.findFile(name)?.delete()
            val file = tree.createFile("text/markdown", name) ?: continue
            val out = context.contentResolver.openOutputStream(file.uri) ?: continue
            out.use {
                it.write(
                    toMarkdown(note, links[note.id].orEmpty().mapNotNull { id -> labels[id]?.name })
                        .toByteArray(Charsets.UTF_8),
                )
            }
            written++
        }
        return ExportOutcome(written, images)
    }

    /** Splits off the --- frontmatter block, if the file starts with one. */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return emptyMap<String, String>() to text
        val firstBreak = text.indexOf('\n')
        val close = text.indexOf("\n---", firstBreak + 1)
        if (close < 0) return emptyMap<String, String>() to text
        val header = text.substring(firstBreak + 1, close)
        val fields = HashMap<String, String>()
        header.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) fields[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        var rest = close + 4
        if (rest < text.length && text[rest] == '\r') rest++
        if (rest < text.length && text[rest] == '\n') rest++
        return fields to text.substring(rest)
    }

    /** Obsidian [[links]] become plain titles on import (an alias wins over the title). */
    internal fun stripWikilinks(text: String): String =
        WIKILINK.replace(text) { match ->
            (match.groupValues[2].ifEmpty { match.groupValues[1] }).substringBefore('#')
        }

    /** Reads every .md file in the picked folder as a new note. Call from an IO thread. */
    suspend fun import(context: Context, db: NotesDb, folderUri: Uri): ImportOutcome {
        val dao = db.dao()
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: error("That folder could not be opened")
        val files = tree.listFiles().filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
        var added = 0
        var failed = 0
        db.withTransaction {
            for (file in files) {
                try {
                    val raw = context.contentResolver.openInputStream(file.uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: continue
                    val (fields, body) = splitFrontmatter(raw)
                    val title = file.name!!.dropLast(3)
                    val clean = stripWikilinks(body)
                    // A file counts as a checklist when every line is an item.
                    val lines = clean.lineSequence().filter { it.isNotBlank() }.toList()
                    val isChecklist = lines.isNotEmpty() && lines.all { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("[ ]") || trimmed.startsWith("[x]")
                    }
                    val now = System.currentTimeMillis()
                    val created = fields["created"]?.let { runCatching { DATE.parse(it)?.time }.getOrNull() } ?: now
                    val updated = fields["updated"]?.let { runCatching { DATE.parse(it)?.time }.getOrNull() } ?: now
                    val id = dao.insertNote(
                        Note(
                            uuid = UUID.randomUUID().toString(),
                            title = title,
                            body = clean,
                            isChecklist = isChecklist,
                            createdAt = created,
                            updatedAt = updated,
                        ),
                    )
                    fields["labels"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                        dao.addNoteLabel(NoteLabel(id, Backup.ensureLabel(dao, name)))
                    }
                    added++
                } catch (e: Exception) {
                    failed++
                }
            }
        }
        return ImportOutcome(added, failed)
    }
}
