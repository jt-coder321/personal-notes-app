package com.jt.notes

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jt.notes.data.Attachment
import com.jt.notes.data.Backup
import com.jt.notes.data.CheckItem
import com.jt.notes.data.Images
import com.jt.notes.data.KeepImport
import com.jt.notes.data.Label
import com.jt.notes.data.MIGRATION_1_2
import com.jt.notes.data.MIGRATION_2_3
import com.jt.notes.data.MIGRATION_3_4
import com.jt.notes.data.MIGRATION_4_5
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.NoteLabel
import com.jt.notes.data.NotesDb
import com.jt.notes.data.Version
import com.jt.notes.data.WrongPasswordException
import com.jt.notes.data.checklistBodyToText
import com.jt.notes.data.clearChecks
import com.jt.notes.data.isBlank
import com.jt.notes.data.itemsToBody
import com.jt.notes.data.parseItems
import com.jt.notes.data.textToChecklistBody
import com.jt.notes.ui.LinkKind
import com.jt.notes.ui.detectLinks
import com.jt.notes.ui.displayMarkup
import com.jt.notes.ui.styleMarkup
import com.jt.notes.ui.toggleBold
import com.jt.notes.ui.toggleLinePrefix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Checks the app's data logic on a real Android device: storage, backup, import, export. */
@RunWith(AndroidJUnit4::class)
class DataTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun memoryDb(): NotesDb = Room.inMemoryDatabaseBuilder(context, NotesDb::class.java).build()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3B5BFF.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    // ----- checklists -----

    @Test
    fun checklist_roundTrip_keepsChecksAndSubItems() {
        val items = listOf(CheckItem("Milk", false), CheckItem("Oat", true, 1), CheckItem("", false))
        val body = itemsToBody(items)
        assertEquals("[ ] Milk\n  [x] Oat\n[ ] ", body)
        assertEquals(items, parseItems(body))
    }

    @Test
    fun checklist_convertsToTextAndBack() {
        val body = textToChecklistBody("one\n\n- two")
        assertEquals(listOf(CheckItem("one", false), CheckItem("two", false)), parseItems(body))
        assertEquals("one\ntwo", checklistBodyToText(body))
        assertEquals("[ ] a\n[ ] b", clearChecks("[x] a\n[ ] b"))
    }

    @Test
    fun blankNote_isDetected() {
        assertTrue(Note(uuid = "a", createdAt = 0, updatedAt = 0).isBlank())
        assertTrue(Note(uuid = "a", isChecklist = true, body = "[ ] ", createdAt = 0, updatedAt = 0).isBlank())
        assertFalse(Note(uuid = "a", body = "x", createdAt = 0, updatedAt = 0).isBlank())
    }

    // ----- formatting and links -----

    @Test
    fun formatting_headingBulletAndBold() {
        val start = TextFieldValue("first\nsecond", TextRange(8))
        val heading = toggleLinePrefix(start, "# ")
        assertEquals("first\n# second", heading.text)
        assertEquals(10, heading.selection.start)
        val bullet = toggleLinePrefix(heading, "- ")
        assertEquals("first\n- second", bullet.text)
        assertEquals("first\nsecond", toggleLinePrefix(bullet, "- ").text)

        val bold = toggleBold(TextFieldValue("make this bold", TextRange(5, 9)))
        assertEquals("make **this** bold", bold.text)
        assertEquals("make this bold", toggleBold(bold).text)
        val empty = toggleBold(TextFieldValue("ab", TextRange(1)))
        assertEquals("a****b", empty.text)
        assertEquals(3, empty.selection.start)
    }

    @Test
    fun formatting_rendersForCardsAndEditor() {
        val text = "# Title\n- item\nsome **bold** here"
        assertEquals("Title\n• item\nsome bold here", displayMarkup(text).text)
        // The editor keeps every character so the cursor never jumps.
        assertEquals(text, styleMarkup(text, androidx.compose.ui.graphics.Color.Gray).text)
    }

    @Test
    fun links_areDetected() {
        val found = detectLinks("See https://example.com/page or mail jo@example.org, call 562-555-0199.")
        assertTrue(found.any { it.kind == LinkKind.WEB && it.text.startsWith("https://example.com") })
        assertTrue(found.any { it.kind == LinkKind.EMAIL && it.text == "jo@example.org" })
        assertTrue(found.any { it.kind == LinkKind.PHONE && it.text.contains("562-555-0199") })
        assertTrue(detectLinks("nothing to see").isEmpty())
    }

    // ----- backup and restore -----

    private suspend fun seed(db: NotesDb): String {
        val dao = db.dao()
        val id = dao.insertNote(
            Note(
                uuid = "u1", title = "Trip", body = "[ ] a\n  [x] b", isChecklist = true, color = 2,
                pinned = true, createdAt = 1, updatedAt = 5, reminderAt = 99,
            )
        )
        dao.insertNote(Note(uuid = "u2", title = "Plain", body = "hello", archived = true, createdAt = 2, updatedAt = 6))
        val label = dao.insertLabel(Label(name = "Home"))
        dao.addNoteLabel(NoteLabel(id, label))
        val name = Images.save(context, jpeg().inputStream())
        dao.insertAttachment(Attachment(noteId = id, fileName = name, createdAt = 1))
        return name
    }

    private suspend fun assertSeedRestored(db: NotesDb, picture: String) {
        val dao = db.dao()
        val trip = dao.getNoteByUuid("u1")!!
        assertEquals("Trip", trip.title)
        assertEquals("[ ] a\n  [x] b", trip.body)
        assertTrue(trip.isChecklist)
        assertTrue(trip.pinned)
        assertEquals(2, trip.color)
        assertEquals(99L, trip.reminderAt)
        assertTrue(dao.getNoteByUuid("u2")!!.archived)
        assertEquals(listOf("Home"), dao.allLabels().map { it.name })
        assertEquals(1, dao.labelIdsFor(trip.id).size)
        assertEquals(listOf(picture), dao.attachmentsFor(trip.id).map { it.fileName })
        assertTrue("picture file restored", Images.file(context, picture).length() > 0)
    }

    @Test
    fun backup_plain_roundTrip() = runBlocking<Unit> {
        val source = memoryDb()
        val picture = seed(source)
        val bytes = ByteArrayOutputStream().also { Backup.export(context, source.dao(), it, null) }.toByteArray()
        assertEquals("zip header", 'P'.code.toByte(), bytes[0])
        Images.file(context, picture).delete()

        val staged = Backup.stage(context, bytes.inputStream(), "test-plain.tmp")
        assertFalse(Backup.isEncrypted(staged))
        val target = memoryDb()
        val result = Backup.import(context, target, staged, null)
        assertEquals(2, result.added)
        assertSeedRestored(target, picture)

        // Restoring the same file again changes nothing and adds no duplicates.
        val again = Backup.import(context, target, staged, null)
        assertEquals(0, again.added)
        assertEquals(2, again.skipped)
        assertEquals(2, target.dao().allNotes().size)
        assertEquals(1, target.dao().allAttachments().size)
    }

    @Test
    fun backup_encrypted_roundTrip_andWrongPasswordIsRejected() = runBlocking<Unit> {
        val source = memoryDb()
        val picture = seed(source)
        val bytes = ByteArrayOutputStream().also { Backup.export(context, source.dao(), it, "s3cret pass") }.toByteArray()
        assertFalse("not readable as a zip", bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        assertFalse("note text is not visible in the file", String(bytes, Charsets.ISO_8859_1).contains("Trip"))
        Images.file(context, picture).delete()

        val staged = Backup.stage(context, bytes.inputStream(), "test-encrypted.tmp")
        assertTrue(Backup.isEncrypted(staged))
        val target = memoryDb()
        try {
            Backup.import(context, target, staged, "wrong")
            fail("a wrong password must not restore anything")
        } catch (expected: WrongPasswordException) {
        }
        try {
            Backup.import(context, target, staged, null)
            fail("a missing password must not restore anything")
        } catch (expected: WrongPasswordException) {
        }
        assertTrue(target.dao().allNotes().isEmpty())

        Backup.import(context, target, staged, "s3cret pass")
        assertSeedRestored(target, picture)
    }

    @Test
    fun restore_keepsTheNewerCopyOfANote() = runBlocking<Unit> {
        val source = memoryDb()
        source.dao().insertNote(Note(uuid = "same", title = "Old text", createdAt = 1, updatedAt = 10))
        val bytes = ByteArrayOutputStream().also { Backup.export(context, source.dao(), it, null) }.toByteArray()
        val staged = Backup.stage(context, bytes.inputStream(), "test-merge.tmp")

        val newer = memoryDb()
        newer.dao().insertNote(Note(uuid = "same", title = "Newer on phone", createdAt = 1, updatedAt = 20))
        assertEquals(1, Backup.import(context, newer, staged, null).skipped)
        assertEquals("Newer on phone", newer.dao().getNoteByUuid("same")!!.title)

        val older = memoryDb()
        older.dao().insertNote(Note(uuid = "same", title = "Stale on phone", createdAt = 1, updatedAt = 3))
        assertEquals(1, Backup.import(context, older, staged, null).updated)
        assertEquals("Old text", older.dao().getNoteByUuid("same")!!.title)
    }

    @Test
    fun restore_readsTheFirstVersionsJsonBackup() = runBlocking<Unit> {
        val json = """
            {"app":"personal-notes","version":1,"labels":["Work"],
             "notes":[{"uuid":"old1","title":"From v1","body":"text","isChecklist":false,"color":1,
                       "pinned":false,"archived":false,"createdAt":1,"updatedAt":2,"labels":["Work"]}]}
        """.trimIndent()
        val staged = Backup.stage(context, json.byteInputStream(), "test-json.tmp")
        val db = memoryDb()
        assertEquals(1, Backup.import(context, db, staged, null).added)
        assertEquals("From v1", db.dao().getNoteByUuid("old1")!!.title)
        assertEquals(listOf("Work"), db.dao().allLabels().map { it.name })
    }

    @Test
    fun restore_rejectsAFileThatIsNotABackup() = runBlocking<Unit> {
        val staged = Backup.stage(context, "{\"hello\":1}".byteInputStream(), "test-bad.tmp")
        try {
            Backup.import(context, memoryDb(), staged, null)
            fail("must reject")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ----- Google Keep import -----

    @Test
    fun keepImport_readsNotesListsLabelsAndPictures() = runBlocking<Unit> {
        val zipFile = File(context.cacheDir, "test-keep.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "Takeout/Keep/Text note.json",
                """{"color":"YELLOW","isTrashed":false,"isPinned":true,"isArchived":false,
                    "textContent":"Remember the milk","title":"Shopping",
                    "userEditedTimestampUsec":1700000000000000,"createdTimestampUsec":1690000000000000,
                    "labels":[{"name":"Home"}],
                    "attachments":[{"filePath":"photo.jpg","mimetype":"image/jpeg"}]}""".toByteArray(),
            )
            put(
                "Takeout/Keep/List.json",
                """{"color":"DEFAULT","isTrashed":false,"isPinned":false,"isArchived":true,
                    "listContent":[{"text":"Eggs","isChecked":true},{"text":"Bread","isChecked":false}],
                    "title":"List","userEditedTimestampUsec":1700000001000000,
                    "createdTimestampUsec":1690000000000000}""".toByteArray(),
            )
            put("Takeout/Keep/photo.jpg", jpeg())
            put("Takeout/archive_browser.html", "<html></html>".toByteArray())
            put("Takeout/Other/settings.json", "{\"a\":1}".toByteArray())
        }
        val db = memoryDb()
        val result = KeepImport.import(context, db, zipFile)
        assertEquals(2, result.added)
        val dao = db.dao()
        val shopping = dao.allNotes().first { it.title == "Shopping" }
        assertEquals("Remember the milk", shopping.body)
        assertTrue(shopping.pinned)
        assertEquals(1, shopping.color)
        assertEquals(1_700_000_000_000L, shopping.updatedAt)
        assertEquals(1, dao.attachmentsFor(shopping.id).size)
        assertEquals(1, dao.labelIdsFor(shopping.id).size)
        val list = dao.allNotes().first { it.title == "List" }
        assertTrue(list.isChecklist)
        assertTrue(list.archived)
        assertEquals(listOf(CheckItem("Eggs", true), CheckItem("Bread", false)), parseItems(list.body))

        // Importing the same export again adds nothing.
        val again = KeepImport.import(context, db, zipFile)
        assertEquals(0, again.added)
        assertEquals(2, again.skipped)
        assertEquals(2, dao.allNotes().size)
    }

    // ----- export -----

    @Test
    fun export_markdownAndPdf() {
        val checklist = Note(uuid = "e", title = "Trip", body = "[ ] a\n  [x] b", isChecklist = true, createdAt = 0, updatedAt = 0)
        assertEquals("# Trip\n\n- [ ] a\n  - [x] b\n", NoteExport.toMarkdown(checklist))

        val long = Note(uuid = "p", title = "Long", body = (1..400).joinToString("\n") { "Line $it" }, createdAt = 0, updatedAt = 0)
        val pdf = ByteArrayOutputStream().also { NoteExport.writePdf(long, it) }.toByteArray()
        assertEquals("%PDF", String(pdf, 0, 4, Charsets.US_ASCII))
        assertTrue("several pages worth of data", pdf.size > 2000)
    }

    // ----- storage -----

    @Test
    fun pictures_areScaledDownWhenLoaded() {
        val name = Images.save(context, jpeg().inputStream())
        val small = Images.load(context, name, 100)
        assertNotNull(small)
        assertTrue(maxOf(small!!.width, small.height) <= 200)
        assertNull(Images.load(context, "missing.jpg", 100))
        Images.file(context, name).delete()
    }

    @Test
    fun database_versionHistoryIsTrimmed_andBlankNotesAreCleaned() = runBlocking<Unit> {
        val db = memoryDb()
        val dao = db.dao()
        val id = dao.insertNote(Note(uuid = "v", title = "Kept", createdAt = 0, updatedAt = 0))
        repeat(25) { dao.insertVersion(Version(noteId = id, title = "t$it", body = "", isChecklist = false, savedAt = it.toLong())) }
        dao.trimVersions(id, 20)
        val versions = dao.versionsFor(id)
        assertEquals(20, versions.size)
        assertEquals("t24", versions.first().title)

        val blank = dao.insertNote(Note(uuid = "b1", createdAt = 0, updatedAt = 0))
        val blankWithPicture = dao.insertNote(Note(uuid = "b2", createdAt = 0, updatedAt = 0))
        dao.insertAttachment(Attachment(noteId = blankWithPicture, fileName = "x.jpg", createdAt = 0))
        dao.deleteBlankNotes()
        assertNull(dao.getNote(blank))
        assertNotNull(dao.getNote(blankWithPicture))

        // Deleting a note removes its history and picture records too.
        dao.deleteNote(id)
        assertTrue(dao.versionsFor(id).isEmpty())
    }

    @Test
    fun database_upgradesFromTheFirstVersionWithoutLosingNotes() = runBlocking<Unit> {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `isChecklist` INTEGER NOT NULL, `color` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `trashedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_uuid` ON `notes` (`uuid`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `labels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_labels_name` ON `labels` (`name`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `note_labels` (`noteId` INTEGER NOT NULL, `labelId` INTEGER NOT NULL, PRIMARY KEY(`noteId`, `labelId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`labelId`) REFERENCES `labels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_labels_labelId` ON `note_labels` (`labelId`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }
            ).build()
        )
        v1.writableDatabase.execSQL(
            "INSERT INTO notes (uuid, title, body, isChecklist, color, pinned, archived, createdAt, updatedAt) " +
                "VALUES ('old', 'Written before the update', 'still here', 0, 0, 1, 0, 1, 2)"
        )
        v1.close()

        val db = Room.databaseBuilder(context, NotesDb::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        val dao = db.dao()
        val note = dao.getNoteByUuid("old")!!
        assertEquals("Written before the update", note.title)
        assertNull(note.reminderAt)
        assertEquals(1, note.remindPings)
        assertEquals(5, note.remindGapMin)
        assertFalse(note.remindAlarm)
        assertEquals(0, note.remindDone)
        assertEquals(0, note.remindRepeat)
        assertNull(note.remindAnchor)
        assertEquals(2L, note.position)
        dao.insertAttachment(Attachment(noteId = note.id, fileName = "a.jpg", createdAt = 1))
        dao.insertVersion(Version(noteId = note.id, title = "t", body = "b", isChecklist = false, savedAt = 1))
        dao.updateNote(note.copy(reminderAt = 5))
        assertEquals(5L, dao.getNote(note.id)!!.reminderAt)
        db.close()
        context.deleteDatabase(name)
    }
}

