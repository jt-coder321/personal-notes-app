package com.jt.notes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A note. A checklist is a note whose body holds one item per line,
 * each line starting with "[ ] " (open) or "[x] " (done); two leading
 * spaces mark a sub-item.
 */
@Entity(tableName = "notes", indices = [Index(value = ["uuid"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val title: String = "",
    val body: String = "",
    val isChecklist: Boolean = false,
    val color: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val reminderAt: Long? = null,
)

@Entity(tableName = "labels", indices = [Index(value = ["name"], unique = true)])
data class Label(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "note_labels",
    primaryKeys = ["noteId", "labelId"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Label::class,
            parentColumns = ["id"],
            childColumns = ["labelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("labelId")],
)
data class NoteLabel(val noteId: Long, val labelId: Long)

/** A picture attached to a note. The file lives in the app's private images folder. */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val fileName: String,
    val createdAt: Long,
)

/** An earlier state of a note's text, kept so it can be brought back. */
@Entity(
    tableName = "versions",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class Version(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val body: String,
    val isChecklist: Boolean,
    val savedAt: Long,
)

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<Note>>

    @Query("SELECT * FROM labels ORDER BY name COLLATE NOCASE")
    fun observeLabels(): Flow<List<Label>>

    @Query("SELECT * FROM note_labels")
    fun observeNoteLabels(): Flow<List<NoteLabel>>

    @Query("SELECT * FROM attachments ORDER BY createdAt")
    fun observeAttachments(): Flow<List<Attachment>>

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<Note>

    @Query("SELECT * FROM labels")
    suspend fun allLabels(): List<Label>

    @Query("SELECT * FROM note_labels")
    suspend fun allNoteLabels(): List<NoteLabel>

    @Query("SELECT * FROM attachments ORDER BY createdAt")
    suspend fun allAttachments(): List<Attachment>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: Long): Note?

    @Query("SELECT * FROM notes WHERE uuid = :uuid")
    suspend fun getNoteByUuid(uuid: String): Note?

    @Query("SELECT * FROM notes WHERE reminderAt IS NOT NULL AND trashedAt IS NULL")
    suspend fun notesWithReminders(): List<Note>

    @Query("UPDATE notes SET reminderAt = NULL WHERE id = :id")
    suspend fun clearReminder(id: Long)

    @Insert
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("DELETE FROM notes WHERE trashedAt IS NOT NULL AND trashedAt < :cutoff")
    suspend fun purgeTrash(cutoff: Long)

    @Query("DELETE FROM notes WHERE trashedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query(
        "DELETE FROM notes WHERE trim(title) = '' AND (trim(body) = '' OR trim(body) = '[ ]') " +
            "AND id NOT IN (SELECT noteId FROM attachments)"
    )
    suspend fun deleteBlankNotes()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabel(label: Label): Long

    @Query("SELECT * FROM labels WHERE name = :name")
    suspend fun labelByName(name: String): Label?

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun updateLabel(label: Label)

    @Delete
    suspend fun deleteLabel(label: Label)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNoteLabel(noteLabel: NoteLabel)

    @Delete
    suspend fun removeNoteLabel(noteLabel: NoteLabel)

    @Query("SELECT labelId FROM note_labels WHERE noteId = :noteId")
    suspend fun labelIdsFor(noteId: Long): List<Long>

    @Insert
    suspend fun insertAttachment(attachment: Attachment): Long

    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY createdAt")
    suspend fun attachmentsFor(noteId: Long): List<Attachment>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Insert
    suspend fun insertVersion(version: Version): Long

    @Query("SELECT * FROM versions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC")
    suspend fun versionsFor(noteId: Long): List<Version>

    @Query(
        "DELETE FROM versions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM versions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimVersions(noteId: Long, keep: Int)
}

/** Version 2 adds reminders, pictures and version history. Existing notes are kept. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `reminderAt` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noteId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_noteId` ON `attachments` (`noteId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noteId` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                "`isChecklist` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_versions_noteId` ON `versions` (`noteId`)")
    }
}

@Database(
    entities = [Note::class, Label::class, NoteLabel::class, Attachment::class, Version::class],
    version = 2,
    exportSchema = false,
)
abstract class NotesDb : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        @Volatile
        private var instance: NotesDb? = null

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NotesDb::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
