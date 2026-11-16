package com.jt.notes.data

import android.content.Context
import androidx.room.ColumnInfo
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
    /** How many times the reminder pings before it stops. */
    @ColumnInfo(defaultValue = "1") val remindPings: Int = 1,
    /** Minutes between pings. */
    @ColumnInfo(defaultValue = "5") val remindGapMin: Int = 5,
    /** Loud alarm sound instead of a quiet notification. */
    @ColumnInfo(defaultValue = "0") val remindAlarm: Boolean = false,
    /** Pings already sent in the current series. */
    @ColumnInfo(defaultValue = "0") val remindDone: Int = 0,
    /** Repeat for the reminder: 0 = once, 1 = daily, 2 = weekly. See ReminderPlan. */
    @ColumnInfo(defaultValue = "0") val remindRepeat: Int = 0,
    /** The time the current reminder occurrence was picked for. Pings move reminderAt forward; this stays put, so repeats land on the same time of day and editing pre-fills the original pick. */
    val remindAnchor: Long? = null,
    /** Place in "My order". Bigger comes first. New notes start at the top. */
    @ColumnInfo(defaultValue = "0") val position: Long = System.currentTimeMillis(),
)

@Entity(tableName = "labels", indices = [Index(value = ["name"], unique = true)])
data class Label(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** The parent label, for nesting. Null means top level. */
    val parentId: Long? = null,
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

/** A voice recording attached to a note. The file lives in the app's private audio folder. */
@Entity(
    tableName = "audio_clips",
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
data class AudioClip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val fileName: String,
    val durationMs: Long,
    val createdAt: Long,
)

/** A saved starter for new notes: title, body and checklist state, reused as-is. */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val isChecklist: Boolean = false,
    val createdAt: Long,
)

/** A reminder that fires when the phone arrives at, or leaves, a place. One per note. */
@Entity(
    tableName = "place_reminders",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["noteId"], unique = true)],
)
data class PlaceReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val latitude: Double,
    val longitude: Double,
    /** Radius in metres around the place. */
    val radiusM: Int,
    /** 0 = fire on arriving, 1 = fire on leaving. See PlaceReminders. */
    val trigger: Int,
    /** True once it has fired; a place reminder fires once. */
    @ColumnInfo(defaultValue = "0") val fired: Boolean = false,
    /** Whether the phone was inside the radius at the last check. Null until the first check. */
    val lastInside: Boolean? = null,
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

    @Query("SELECT * FROM audio_clips ORDER BY createdAt")
    fun observeAudioClips(): Flow<List<AudioClip>>

    @Query("SELECT * FROM templates ORDER BY title COLLATE NOCASE")
    fun observeTemplates(): Flow<List<Template>>

    @Query("SELECT * FROM place_reminders")
    fun observePlaceReminders(): Flow<List<PlaceReminder>>

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

    @Query("UPDATE notes SET reminderAt = :at, remindDone = :done WHERE id = :id")
    suspend fun setReminderState(id: Long, at: Long?, done: Int)

    /** Moves a repeating reminder to its next occurrence, which becomes the new anchor. */
    @Query("UPDATE notes SET reminderAt = :at, remindDone = :done, remindAnchor = :anchor WHERE id = :id")
    suspend fun setReminderSeries(id: Long, at: Long?, done: Int, anchor: Long?)

    /** Clears a reminder entirely, including its repeat chain. */
    @Query("UPDATE notes SET reminderAt = NULL, remindDone = 0, remindAnchor = NULL, remindRepeat = 0 WHERE id = :id")
    suspend fun clearReminderFull(id: Long)

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
            "AND id NOT IN (SELECT noteId FROM attachments) " +
            "AND id NOT IN (SELECT noteId FROM audio_clips)"
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

    /** Moves a deleted label's children to the top level. */
    @Query("UPDATE labels SET parentId = NULL WHERE parentId = :id")
    suspend fun clearChildrenParent(id: Long)

    @Query("SELECT * FROM templates ORDER BY title COLLATE NOCASE")
    suspend fun allTemplates(): List<Template>

    @Insert
    suspend fun insertTemplate(template: Template): Long

    @Delete
    suspend fun deleteTemplate(template: Template)

    @Query("SELECT * FROM place_reminders")
    suspend fun allPlaceReminders(): List<PlaceReminder>

    @Query("SELECT * FROM place_reminders WHERE noteId = :noteId")
    suspend fun placeReminderFor(noteId: Long): PlaceReminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaceReminder(reminder: PlaceReminder): Long

    @Update
    suspend fun updatePlaceReminder(reminder: PlaceReminder)

    @Query("DELETE FROM place_reminders WHERE noteId = :noteId")
    suspend fun deletePlaceReminder(noteId: Long)

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
    suspend fun insertAudioClip(clip: AudioClip): Long

    @Query("SELECT * FROM audio_clips WHERE noteId = :noteId ORDER BY createdAt")
    suspend fun audioClipsFor(noteId: Long): List<AudioClip>

    @Query("DELETE FROM audio_clips WHERE id = :id")
    suspend fun deleteAudioClip(id: Long)

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

/** Version 3 adds repeating pings and alarm sound to reminders. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindPings` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindGapMin` INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindAlarm` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindDone` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 4 adds the order people drag notes into. Existing notes keep their current order. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `notes` SET `position` = `updatedAt`")
    }
}

/** Version 5 adds repeating reminders (a repeat setting plus the occurrence anchor) and voice recordings. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindRepeat` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `remindAnchor` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audio_clips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noteId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_clips_noteId` ON `audio_clips` (`noteId`)")
    }
}

/** Version 6 adds label nesting, saved templates, and place reminders. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `labels` ADD COLUMN `parentId` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `body` TEXT NOT NULL, `isChecklist` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `place_reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noteId` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                "`radiusM` INTEGER NOT NULL, `trigger` INTEGER NOT NULL, `fired` INTEGER NOT NULL DEFAULT 0, " +
                "`lastInside` INTEGER, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_place_reminders_noteId` ON `place_reminders` (`noteId`)")
    }
}

@Database(
    entities = [Note::class, Label::class, NoteLabel::class, Attachment::class, AudioClip::class, Version::class, Template::class, PlaceReminder::class],
    version = 6,
    exportSchema = false,
)
abstract class NotesDb : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        @Volatile
        private var instance: NotesDb? = null

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NotesDb::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}


