package com.jt.notes.data

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Imports notes from a Google Takeout zip that contains a Keep export.
 * Each Keep note is one JSON file with fields such as title, textContent,
 * listContent, isPinned, isArchived, isTrashed, labels and attachments.
 * Importing the same zip twice does not create duplicates.
 */
object KeepImport {
    // Keep's color names mapped to the closest color here. Unknown names get no color.
    private val COLORS = mapOf(
        "YELLOW" to 1, "GREEN" to 2, "BLUE" to 3, "CERULEAN" to 3, "PINK" to 4, "RED" to 4,
        "ORANGE" to 5, "BROWN" to 5, "PURPLE" to 6, "TEAL" to 7,
    )

    suspend fun import(context: Context, db: NotesDb, staged: File): ImportResult {
        val dao = db.dao()
        var added = 0
        var skipped = 0
        ZipFile(staged).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            db.withTransaction {
                for (entry in entries) {
                    val o = try {
                        JSONObject(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
                    } catch (e: Exception) {
                        continue
                    }
                    // Only Keep notes carry this field; other JSON files in a Takeout are ignored.
                    if (!o.has("userEditedTimestampUsec")) continue

                    val uuid = "keep-" + UUID.nameUUIDFromBytes(entry.name.toByteArray(Charsets.UTF_8))
                    if (dao.getNoteByUuid(uuid) != null) {
                        skipped++
                        continue
                    }
                    val list = o.optJSONArray("listContent")
                    val isChecklist = list != null
                    val body = if (list != null) {
                        val items = (0 until list.length()).map {
                            val item = list.getJSONObject(it)
                            CheckItem(item.optString("text"), item.optBoolean("isChecked"))
                        }
                        itemsToBody(items)
                    } else {
                        o.optString("textContent")
                    }
                    val edited = o.optLong("userEditedTimestampUsec") / 1000
                    val created = o.optLong("createdTimestampUsec", o.optLong("userEditedTimestampUsec")) / 1000
                    val noteId = dao.insertNote(
                        Note(
                            uuid = uuid,
                            title = o.optString("title"),
                            body = body,
                            isChecklist = isChecklist,
                            color = COLORS[o.optString("color")] ?: 0,
                            pinned = o.optBoolean("isPinned"),
                            archived = o.optBoolean("isArchived"),
                            trashedAt = if (o.optBoolean("isTrashed")) System.currentTimeMillis() else null,
                            createdAt = created,
                            updatedAt = edited,
                        )
                    )
                    added++

                    val labels = o.optJSONArray("labels")
                    if (labels != null) {
                        for (i in 0 until labels.length()) {
                            val name = labels.getJSONObject(i).optString("name").trim()
                            if (name.isNotEmpty()) dao.addNoteLabel(NoteLabel(noteId, Backup.ensureLabel(dao, name)))
                        }
                    }

                    val attachments = o.optJSONArray("attachments")
                    if (attachments != null) {
                        val folder = entry.name.substringBeforeLast('/', "")
                        for (i in 0 until attachments.length()) {
                            val a = attachments.getJSONObject(i)
                            if (!a.optString("mimetype").startsWith("image/")) continue
                            val path = a.optString("filePath")
                            if (path.isEmpty()) continue
                            val imageEntry = zip.getEntry(if (folder.isEmpty()) path else "$folder/$path") ?: continue
                            val name = zip.getInputStream(imageEntry).use { Images.save(context, it) }
                            dao.insertAttachment(
                                Attachment(noteId = noteId, fileName = name, createdAt = System.currentTimeMillis() + i)
                            )
                        }
                    }
                }
            }
        }
        require(added + skipped > 0) { "No Google Keep notes were found in that file" }
        return ImportResult(added, 0, skipped)
    }
}
