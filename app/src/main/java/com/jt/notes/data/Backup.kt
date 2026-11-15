package com.jt.notes.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

/**
 * Backup format: one JSON file holding every note and label.
 * Restoring merges by note uuid: new notes are added, and an existing note
 * is replaced only when the copy in the file was edited more recently.
 */
object Backup {
    private const val APP_ID = "personal-notes"

    suspend fun export(dao: NotesDao): Pair<String, Int> {
        val notes = dao.allNotes()
        val labelsById = dao.allLabels().associateBy { it.id }
        val labelIdsByNote = dao.allNoteLabels().groupBy({ it.noteId }, { it.labelId })

        val notesJson = JSONArray()
        for (n in notes) {
            val o = JSONObject()
            o.put("uuid", n.uuid)
            o.put("title", n.title)
            o.put("body", n.body)
            o.put("isChecklist", n.isChecklist)
            o.put("color", n.color)
            o.put("pinned", n.pinned)
            o.put("archived", n.archived)
            n.trashedAt?.let { o.put("trashedAt", it) }
            o.put("createdAt", n.createdAt)
            o.put("updatedAt", n.updatedAt)
            val names = JSONArray()
            labelIdsByNote[n.id].orEmpty().mapNotNull { labelsById[it]?.name }.forEach { names.put(it) }
            o.put("labels", names)
            notesJson.put(o)
        }

        val labelsJson = JSONArray()
        labelsById.values.forEach { labelsJson.put(it.name) }

        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("labels", labelsJson)
        root.put("notes", notesJson)
        return root.toString(2) to notes.size
    }

    suspend fun import(db: NotesDb, text: String): ImportResult {
        val root = JSONObject(text)
        require(root.optString("app") == APP_ID) { "This is not a Notes backup file" }
        val dao = db.dao()
        var added = 0
        var updated = 0
        var skipped = 0

        db.withTransaction {
            val labelsJson = root.optJSONArray("labels") ?: JSONArray()
            for (i in 0 until labelsJson.length()) ensureLabel(dao, labelsJson.getString(i))

            val notesJson = root.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until notesJson.length()) {
                val o = notesJson.getJSONObject(i)
                val incoming = Note(
                    uuid = o.getString("uuid"),
                    title = o.optString("title"),
                    body = o.optString("body"),
                    isChecklist = o.optBoolean("isChecklist"),
                    color = o.optInt("color"),
                    pinned = o.optBoolean("pinned"),
                    archived = o.optBoolean("archived"),
                    trashedAt = if (o.has("trashedAt")) o.getLong("trashedAt") else null,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
                val existing = dao.getNoteByUuid(incoming.uuid)
                val noteId: Long = when {
                    existing == null -> {
                        added++
                        dao.insertNote(incoming)
                    }
                    incoming.updatedAt > existing.updatedAt -> {
                        updated++
                        dao.updateNote(incoming.copy(id = existing.id))
                        existing.id
                    }
                    else -> {
                        skipped++
                        continue
                    }
                }
                val names = o.optJSONArray("labels") ?: JSONArray()
                for (j in 0 until names.length()) {
                    dao.addNoteLabel(NoteLabel(noteId, ensureLabel(dao, names.getString(j))))
                }
            }
        }
        return ImportResult(added, updated, skipped)
    }

    private suspend fun ensureLabel(dao: NotesDao, name: String): Long =
        dao.labelByName(name)?.id ?: dao.insertLabel(Label(name = name))
}
