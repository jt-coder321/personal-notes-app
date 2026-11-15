package com.jt.notes

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jt.notes.data.Backup
import com.jt.notes.data.Label
import com.jt.notes.data.Note
import com.jt.notes.data.NoteLabel
import com.jt.notes.data.NotesDb
import com.jt.notes.data.isBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Filter {
    data object Notes : Filter
    data object Archive : Filter
    data object Trash : Filter
    data class ByLabel(val labelId: Long) : Filter
}

data class NoteItem(val note: Note, val labels: List<Label>)

private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NotesDb.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // Keeps note writes and the editor's reads in order, so reopening a note
    // (for example when the phone is folded or unfolded) never shows stale text.
    private val writeLock = Mutex()

    val filter = MutableStateFlow<Filter>(Filter.Notes)
    val query = MutableStateFlow("")
    val gridView = MutableStateFlow(prefs.getBoolean("grid", true))
    val lastBackup = MutableStateFlow(prefs.getLong("lastBackup", 0L))

    val labels: StateFlow<List<Label>> =
        dao.observeLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val noteLabels: StateFlow<List<NoteLabel>> =
        dao.observeNoteLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val items: StateFlow<List<NoteItem>> =
        combine(dao.observeNotes(), noteLabels, labels, filter, query) { notes, links, labels, filter, query ->
            val labelsById = labels.associateBy { it.id }
            val labelIdsByNote = links.groupBy({ it.noteId }, { it.labelId })
            val q = query.trim()
            notes
                .map { note ->
                    NoteItem(note, labelIdsByNote[note.id].orEmpty().mapNotNull { labelsById[it] })
                }
                .filter { item ->
                    val n = item.note
                    if (q.isNotEmpty()) {
                        // Search looks through everything except the trash.
                        n.trashedAt == null && (
                            n.title.contains(q, ignoreCase = true) ||
                                n.body.contains(q, ignoreCase = true) ||
                                item.labels.any { it.name.contains(q, ignoreCase = true) }
                            )
                    } else when (filter) {
                        Filter.Notes -> !n.archived && n.trashedAt == null
                        Filter.Archive -> n.archived && n.trashedAt == null
                        Filter.Trash -> n.trashedAt != null
                        is Filter.ByLabel -> n.trashedAt == null && item.labels.any { it.id == filter.labelId }
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MS)
            dao.deleteBlankNotes()
        }
    }

    fun setGridView(grid: Boolean) {
        gridView.value = grid
        prefs.edit().putBoolean("grid", grid).apply()
    }

    // ----- notes -----

    suspend fun createNote(isChecklist: Boolean): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertNote(
            Note(
                uuid = UUID.randomUUID().toString(),
                isChecklist = isChecklist,
                body = if (isChecklist) "[ ] " else "",
                createdAt = now,
                updatedAt = now,
            )
        )
        (filter.value as? Filter.ByLabel)?.let { dao.addNoteLabel(NoteLabel(id, it.labelId)) }
        return id
    }

    suspend fun getNote(id: Long): Note? = writeLock.withLock { dao.getNote(id) }

    /** Writes the note exactly as given. */
    fun save(note: Note) {
        viewModelScope.launch { writeLock.withLock { dao.updateNote(note) } }
    }

    /** Writes a change made outside the editor and marks the note as edited now. */
    fun update(note: Note) = save(note.copy(updatedAt = System.currentTimeMillis()))

    /** Called when leaving the editor: keeps the note, or drops it if nothing was typed. */
    fun finishEditing(note: Note) {
        viewModelScope.launch {
            writeLock.withLock {
                if (note.isBlank()) dao.deleteNote(note.id) else dao.updateNote(note)
            }
        }
    }

    fun trash(note: Note) = update(note.copy(trashedAt = System.currentTimeMillis(), pinned = false))

    fun restore(note: Note) = update(note.copy(trashedAt = null))

    fun deleteForever(note: Note) {
        viewModelScope.launch { dao.deleteNote(note.id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { dao.emptyTrash() }
    }

    // ----- labels -----

    fun addLabel(name: String, attachToNoteId: Long? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = dao.labelByName(trimmed)?.id ?: dao.insertLabel(Label(name = trimmed))
            if (id > 0 && attachToNoteId != null) dao.addNoteLabel(NoteLabel(attachToNoteId, id))
        }
    }

    fun renameLabel(label: Label, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == label.name) return
        viewModelScope.launch { dao.updateLabel(label.copy(name = trimmed)) }
    }

    fun deleteLabel(label: Label) {
        if (filter.value == Filter.ByLabel(label.id)) filter.value = Filter.Notes
        viewModelScope.launch { dao.deleteLabel(label) }
    }

    fun setNoteLabel(noteId: Long, labelId: Long, on: Boolean) {
        viewModelScope.launch {
            val link = NoteLabel(noteId, labelId)
            if (on) dao.addNoteLabel(link) else dao.removeNoteLabel(link)
        }
    }

    // ----- backup -----

    fun exportTo(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val count = withContext(Dispatchers.IO) {
                    val (json, count) = Backup.export(dao)
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("could not open the file")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    count
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastBackup", now).apply()
                lastBackup.value = now
                "Backup saved: $count notes"
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            onDone(message)
        }
    }

    fun importFrom(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("could not open the file")
                    val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                    Backup.import(db, text)
                }
                "Restored: ${result.added} added, ${result.updated} updated, ${result.skipped} unchanged"
            } catch (e: Exception) {
                "Restore failed: ${e.message}"
            }
            onDone(message)
        }
    }
}
