package com.jt.notes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jt.notes.data.Attachment
import com.jt.notes.data.Backup
import com.jt.notes.data.Images
import com.jt.notes.data.KeepImport
import com.jt.notes.data.Label
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.NoteLabel
import com.jt.notes.data.NotesDb
import com.jt.notes.data.Version
import com.jt.notes.data.WrongPasswordException
import com.jt.notes.data.clearChecks
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
import java.io.File
import java.util.UUID

sealed interface Filter {
    data object Notes : Filter
    data object Archive : Filter
    data object Trash : Filter
    data class ByLabel(val labelId: Long) : Filter
}

enum class Sort(val label: String) {
    EDITED("Last edited"),
    CREATED("Date created"),
    TITLE("Title"),
    MANUAL("My order"),
}

data class NoteItem(val note: Note, val labels: List<Label>, val images: List<String>)

/** A change that can still be taken back: the notes as they were before it. */
data class Undo(val message: String, val before: List<Note>, val id: Long = System.nanoTime())

private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
private const val VERSIONS_KEPT = 20

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NotesDb.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val context: Context get() = getApplication<Application>()

    // Keeps note writes and the editor's reads in order, so reopening a note
    // (for example when the phone is folded or unfolded) never shows stale text.
    private val writeLock = Mutex()

    val filter = MutableStateFlow<Filter>(Filter.Notes)
    val query = MutableStateFlow("")
    val gridView = MutableStateFlow(prefs.getBoolean("grid", true))
    val lastBackup = MutableStateFlow(prefs.getLong("lastBackup", 0L))
    val sort = MutableStateFlow(
        Sort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: Sort.EDITED
    )
    val lockEnabled = MutableStateFlow(prefs.getBoolean("lock", false))
    val theme = MutableStateFlow(prefs.getString("theme", "classic") ?: "classic")

    fun setTheme(id: String) {
        prefs.edit().putString("theme", id).apply()
        theme.value = id
    }

    val autoBackupFolder = MutableStateFlow(prefs.getString(AutoBackup.PREF_FOLDER, null))
    val undo = MutableStateFlow<Undo?>(null)

    /** A note the app should open next (from a share, the widget, or a reminder). */
    val pendingOpen = MutableStateFlow<Long?>(null)

    val labels: StateFlow<List<Label>> =
        dao.observeLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val noteLabels: StateFlow<List<NoteLabel>> =
        dao.observeNoteLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val attachments: StateFlow<List<Attachment>> =
        dao.observeAttachments().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allItems =
        combine(dao.observeNotes(), noteLabels, labels, attachments) { notes, links, labels, attachments ->
            val labelsById = labels.associateBy { it.id }
            val labelIdsByNote = links.groupBy({ it.noteId }, { it.labelId })
            val imagesByNote = attachments.groupBy({ it.noteId }, { it.fileName })
            notes.map { note ->
                NoteItem(
                    note = note,
                    labels = labelIdsByNote[note.id].orEmpty().mapNotNull { labelsById[it] },
                    images = imagesByNote[note.id].orEmpty(),
                )
            }
        }

    val items: StateFlow<List<NoteItem>> =
        combine(allItems, filter, query, sort) { all, filter, query, sort ->
            val q = query.trim()
            val shown = all.filter { item ->
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
            when (sort) {
                Sort.EDITED -> shown // already newest first
                Sort.MANUAL -> shown.sortedWith(
                    compareByDescending<NoteItem> { it.note.position }.thenByDescending { it.note.updatedAt }
                )
                Sort.CREATED -> shown.sortedByDescending { it.note.createdAt }
                Sort.TITLE -> shown.sortedWith(
                    compareBy<NoteItem> { it.note.title.isBlank() }.thenBy { it.note.title.lowercase() }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MS)
            dao.deleteBlankNotes()
            withContext(Dispatchers.IO) {
                Images.deleteUnused(context, dao.allAttachments().map { it.fileName }.toSet())
            }
        }
    }

    // ----- settings -----

    fun setGridView(grid: Boolean) {
        gridView.value = grid
        prefs.edit().putBoolean("grid", grid).apply()
    }

    /** The editor holds an older copy of the note, so it must not undo a drag. */
    private suspend fun keepPosition(note: Note): Note =
        dao.getNote(note.id)?.let { note.copy(position = it.position) } ?: note

    /** Saves the order the notes were dragged into, and switches to "My order". */
    fun saveOrder(orderedIds: List<Long>) {
        viewModelScope.launch {
            writeLock.withLock {
                val fresh = orderedIds.mapNotNull { dao.getNote(it) }
                val values = OrderPlan.assign(fresh.map { it.position })
                fresh.forEachIndexed { i, n ->
                    if (n.position != values[i]) dao.updateNote(n.copy(position = values[i]))
                }
            }
            setSort(Sort.MANUAL)
        }
    }

    fun setSort(value: Sort) {
        sort.value = value
        prefs.edit().putString("sort", value.name).apply()
    }

    fun setLock(on: Boolean) {
        lockEnabled.value = on
        prefs.edit().putBoolean("lock", on).apply()
    }

    fun refreshLastBackup() {
        lastBackup.value = prefs.getLong("lastBackup", 0L)
    }

    // ----- notes -----

    suspend fun createNote(isChecklist: Boolean, title: String = "", body: String? = null): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertNote(
            Note(
                uuid = UUID.randomUUID().toString(),
                title = title,
                isChecklist = isChecklist,
                body = body ?: if (isChecklist) "[ ] " else "",
                createdAt = now,
                updatedAt = now,
            )
        )
        (filter.value as? Filter.ByLabel)?.let { dao.addNoteLabel(NoteLabel(id, it.labelId)) }
        return id
    }

    /** Creates a note and asks the screen to open it. */
    fun createAndOpen(isChecklist: Boolean, title: String = "", body: String? = null) {
        viewModelScope.launch { pendingOpen.value = createNote(isChecklist, title, body) }
    }

    suspend fun getNote(id: Long): Note? = writeLock.withLock { dao.getNote(id) }

    /** Writes the note exactly as given. */
    fun save(note: Note) {
        viewModelScope.launch { writeLock.withLock { dao.updateNote(keepPosition(note)) } }
    }

    /**
     * Called when leaving the editor: keeps the note, or drops it if nothing was
     * typed. [original] is the note as it was when opened; if its text changed,
     * that earlier text goes into the version history.
     */
    fun finishEditing(note: Note, original: Note? = null, offerUndo: Undo? = null) {
        viewModelScope.launch {
            writeLock.withLock {
                if (note.isBlank() && dao.attachmentsFor(note.id).isEmpty()) {
                    dao.deleteNote(note.id)
                } else {
                    dao.updateNote(keepPosition(note))
                    if (original != null && !original.isBlank() &&
                        (original.title != note.title || original.body != note.body)
                    ) {
                        dao.insertVersion(
                            Version(
                                noteId = note.id,
                                title = original.title,
                                body = original.body,
                                isChecklist = original.isChecklist,
                                savedAt = original.updatedAt,
                            )
                        )
                        dao.trimVersions(note.id, VERSIONS_KEPT)
                    }
                }
            }
            if (offerUndo != null) undo.value = offerUndo
        }
    }

    suspend fun versions(noteId: Long): List<Version> = dao.versionsFor(noteId)

    /** Applies [change] to every note, and offers Undo when [message] is given. */
    fun bulkUpdate(notes: List<Note>, message: String?, change: (Note) -> Note) {
        if (notes.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            writeLock.withLock { notes.forEach { dao.updateNote(change(it).copy(updatedAt = now)) } }
        }
        if (message != null) undo.value = Undo(message, notes)
    }

    fun trash(notes: List<Note>) {
        notes.forEach { Reminders.cancel(context, it.id) }
        bulkUpdate(notes, if (notes.size == 1) "Moved to Trash" else "${notes.size} notes moved to Trash") {
            it.copy(trashedAt = System.currentTimeMillis(), pinned = false)
        }
    }

    fun setArchived(notes: List<Note>, archived: Boolean) {
        val what = if (notes.size == 1) "Note" else "${notes.size} notes"
        bulkUpdate(notes, if (archived) "$what archived" else "$what unarchived") {
            it.copy(archived = archived, pinned = false)
        }
    }

    fun setPinned(notes: List<Note>, pinned: Boolean) = bulkUpdate(notes, null) { it.copy(pinned = pinned) }

    fun restore(notes: List<Note>) = bulkUpdate(notes, null) { it.copy(trashedAt = null) }

    fun deleteForever(notes: List<Note>) {
        viewModelScope.launch { writeLock.withLock { notes.forEach { dao.deleteNote(it.id) } } }
    }

    fun emptyTrash() {
        viewModelScope.launch { dao.emptyTrash() }
    }

    fun performUndo() {
        val pending = undo.value ?: return
        undo.value = null
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            writeLock.withLock { pending.before.forEach { dao.updateNote(it.copy(updatedAt = now)) } }
            pending.before.forEach { note ->
                val at = note.reminderAt
                if (at != null && at > now) Reminders.schedule(context, note.id, at)
            }
        }
    }

    fun dismissUndo(id: Long) {
        if (undo.value?.id == id) undo.value = null
    }

    /** Copies notes with their labels and pictures. [onDone] gets the last new note's id. */
    fun duplicate(notes: List<Note>, clearBoxes: Boolean = false, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            var last = -1L
            withContext(Dispatchers.IO) {
                for (n in notes) {
                    val now = System.currentTimeMillis()
                    val id = dao.insertNote(
                        n.copy(
                            id = 0,
                            uuid = UUID.randomUUID().toString(),
                            title = if (n.title.isBlank()) "" else n.title + " (copy)",
                            body = if (clearBoxes && n.isChecklist) clearChecks(n.body) else n.body,
                            pinned = false,
                            trashedAt = null,
                            reminderAt = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    dao.labelIdsFor(n.id).forEach { dao.addNoteLabel(NoteLabel(id, it)) }
                    dao.attachmentsFor(n.id).forEach { a ->
                        val source = Images.file(context, a.fileName)
                        if (source.exists()) {
                            val name = source.inputStream().use { Images.save(context, it) }
                            dao.insertAttachment(Attachment(noteId = id, fileName = name, createdAt = a.createdAt))
                        }
                    }
                    last = id
                }
            }
            onDone(last)
        }
    }

    // ----- reminders -----

    /** Opening a reminder notification counts as seeing it, so the remaining pings stop. */
    fun acknowledgeReminder(noteId: Long) {
        viewModelScope.launch {
            Reminders.cancel(context, noteId)
            dao.setReminderState(noteId, null, 0)
        }
    }

    fun setReminder(noteId: Long, at: Long?) {
        if (at == null) Reminders.cancel(context, noteId) else Reminders.schedule(context, noteId, at)
    }

    // ----- pictures -----

    fun addImage(noteId: Long, uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { Images.importFrom(context, uri) } ?: return@launch
            dao.insertAttachment(Attachment(noteId = noteId, fileName = name, createdAt = System.currentTimeMillis()))
        }
    }

    /** Attaches a picture the camera already wrote into the images folder. */
    fun addImageFile(noteId: Long, name: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Images.file(context, name).length() > 0 }
            if (ok) {
                dao.insertAttachment(
                    Attachment(noteId = noteId, fileName = name, createdAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun removeImage(attachment: Attachment) {
        viewModelScope.launch {
            dao.deleteAttachment(attachment.id)
            withContext(Dispatchers.IO) { Images.file(context, attachment.fileName).delete() }
        }
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

    fun addLabelToNotes(notes: List<Note>, labelId: Long) {
        viewModelScope.launch { notes.forEach { dao.addNoteLabel(NoteLabel(it.id, labelId)) } }
    }

    // ----- backup and restore -----

    fun exportTo(uri: Uri, password: String?, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val count = withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("could not open the file")
                    out.use { Backup.export(context, dao, it, password) }
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastBackup", now).apply()
                lastBackup.value = now
                "Backup saved: $count notes" + if (password.isNullOrEmpty()) "" else " (encrypted)"
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            onDone(message)
        }
    }

    private var staged: File? = null

    /** Reads the picked file. Calls [onNeedPassword] if it is encrypted, otherwise restores it. */
    fun importFrom(uri: Uri, onNeedPassword: () -> Unit, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("could not open the file")
                    input.use { Backup.stage(context, it) }
                }
                staged = file
                if (withContext(Dispatchers.IO) { Backup.isEncrypted(file) }) onNeedPassword()
                else finishImport(null, onDone)
            } catch (e: Exception) {
                onDone("Restore failed: ${e.message}")
            }
        }
    }

    fun finishImport(password: String?, onDone: (String) -> Unit) {
        val file = staged ?: return
        viewModelScope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    val r = Backup.import(context, db, file, password)
                    Reminders.rescheduleAll(context)
                    r
                }
                staged = null
                file.delete()
                "Restored: ${result.added} added, ${result.updated} updated, ${result.skipped} unchanged"
            } catch (e: WrongPasswordException) {
                "Wrong password. Nothing was restored."
            } catch (e: Exception) {
                "Restore failed: ${e.message}"
            }
            onDone(message)
        }
    }

    fun importKeep(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("could not open the file")
                    val file = input.use { Backup.stage(context, it, "keep.tmp") }
                    try {
                        KeepImport.import(context, db, file)
                    } finally {
                        file.delete()
                    }
                }
                "Imported ${result.added} notes from Keep" +
                    if (result.skipped > 0) " (${result.skipped} were already here)" else ""
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            onDone(message)
        }
    }

    fun exportPdf(note: Note, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("could not open the file")
                    out.use { NoteExport.writePdf(note, it) }
                }
                "PDF saved"
            } catch (e: Exception) {
                "Could not save the PDF: ${e.message}"
            }
            onDone(message)
        }
    }

    /** Writes the automatic backup right now, so the folder is tested and the status updates. */
    fun backupNow(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val error = AutoBackup.runNow(context)
            lastBackup.value = prefs.getLong("lastBackup", 0L)
            onDone(if (error == null) "Backup saved to your folder" else "Backup failed: $error")
        }
    }

    /** Turns the daily automatic backup on (with a folder) or off (with null). */
    fun setAutoBackupFolder(uri: Uri?) {
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.edit().putString(AutoBackup.PREF_FOLDER, uri.toString()).apply()
            AutoBackup.enable(context)
        } else {
            prefs.edit().remove(AutoBackup.PREF_FOLDER).apply()
            AutoBackup.disable(context)
        }
        autoBackupFolder.value = uri?.toString()
    }
}
