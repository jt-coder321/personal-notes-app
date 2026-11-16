package com.jt.notes.data

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

class WrongPasswordException : Exception("Wrong password")

/**
 * Backup format: a zip holding notes.json (every note and label) and an
 * images/ folder. With a password, the whole zip is encrypted with AES-256-GCM
 * using a key derived from the password (PBKDF2, 120,000 rounds).
 *
 * Restoring merges by note uuid: new notes are added, and an existing note
 * is replaced only when the copy in the file was edited more recently.
 */
object Backup {
    private const val APP_ID = "personal-notes"
    private val MAGIC = "NOTESENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KDF_ROUNDS = 120_000

    // ----- writing -----

    /** Writes a backup to [out] and returns how many notes it holds. Does not close [out]. */
    suspend fun export(context: Context, dao: NotesDao, out: OutputStream, password: String?): Int {
        if (password.isNullOrEmpty()) return writeZip(context, dao, out)
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val encrypted = CipherOutputStream(out, cipher)
        val count = writeZip(context, dao, encrypted)
        encrypted.close() // writes the authentication tag
        return count
    }

    private suspend fun writeZip(context: Context, dao: NotesDao, out: OutputStream): Int {
        val notes = dao.allNotes()
        val labelsById = dao.allLabels().associateBy { it.id }
        val labelIdsByNote = dao.allNoteLabels().groupBy({ it.noteId }, { it.labelId })
        val imagesByNote = dao.allAttachments().groupBy({ it.noteId }, { it.fileName })

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
            n.reminderAt?.let { o.put("reminderAt", it) }
            o.put("position", n.position)
            o.put("remindPings", n.remindPings)
            o.put("remindGapMin", n.remindGapMin)
            o.put("remindAlarm", n.remindAlarm)
            o.put("remindRepeat", n.remindRepeat)
            n.remindAnchor?.let { o.put("remindAnchor", it) }
            o.put("createdAt", n.createdAt)
            o.put("updatedAt", n.updatedAt)
            val names = JSONArray()
            labelIdsByNote[n.id].orEmpty().mapNotNull { labelsById[it]?.name }.forEach { names.put(it) }
            o.put("labels", names)
            val images = JSONArray()
            imagesByNote[n.id].orEmpty().forEach { images.put(it) }
            o.put("images", images)
            notesJson.put(o)
        }
        val labelsJson = JSONArray()
        labelsById.values.forEach { labelsJson.put(it.name) }

        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("labels", labelsJson)
        root.put("notes", notesJson)

        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("notes.json"))
        zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (name in imagesByNote.values.flatten().toSet()) {
            val file = Images.file(context, name)
            if (!file.exists()) continue
            zip.putNextEntry(ZipEntry("images/" + file.name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return notes.size
    }

    // ----- reading -----

    /** Copies the picked file into the app's cache so it can be read more than once. */
    fun stage(context: Context, input: InputStream, name: String = "restore.tmp"): File {
        val file = File(context.cacheDir, name)
        file.outputStream().use { input.copyTo(it) }
        return file
    }

    fun isEncrypted(file: File): Boolean = startsWith(file, MAGIC)

    private fun startsWith(file: File, prefix: ByteArray): Boolean {
        val head = ByteArray(prefix.size)
        val read = file.inputStream().use { it.read(head) }
        return read == prefix.size && head.contentEquals(prefix)
    }

    /** Restores from a staged file: an encrypted backup, a zip backup, or an older JSON backup. */
    suspend fun import(context: Context, db: NotesDb, staged: File, password: String?): ImportResult {
        if (isEncrypted(staged)) {
            if (password.isNullOrEmpty()) throw WrongPasswordException()
            val plain = File(context.cacheDir, "restore-plain.tmp")
            try {
                decrypt(staged, plain, password)
                return importPlain(context, db, plain)
            } finally {
                plain.delete()
            }
        }
        return importPlain(context, db, staged)
    }

    private fun decrypt(source: File, target: File, password: String) {
        try {
            source.inputStream().use { input ->
                input.skip(MAGIC.size.toLong())
                val salt = ByteArray(SALT_BYTES)
                val iv = ByteArray(IV_BYTES)
                if (input.read(salt) != SALT_BYTES || input.read(iv) != IV_BYTES) error("The file is damaged")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                CipherInputStream(input, cipher).use { decrypted ->
                    target.outputStream().use { decrypted.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            throw WrongPasswordException()
        }
        // A wrong password yields garbage rather than a zip.
        if (!startsWith(target, byteArrayOf(0x50, 0x4B))) throw WrongPasswordException()
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, KDF_ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private suspend fun importPlain(context: Context, db: NotesDb, file: File): ImportResult {
        if (!startsWith(file, byteArrayOf(0x50, 0x4B))) {
            // The first version of the app wrote a bare JSON file.
            return importJson(context, db, file.readText(Charsets.UTF_8), null)
        }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("notes.json") ?: throw IllegalArgumentException("This is not a Notes backup file")
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            return importJson(context, db, text, zip)
        }
    }

    private suspend fun importJson(context: Context, db: NotesDb, text: String, zip: ZipFile?): ImportResult {
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
                    reminderAt = if (o.has("reminderAt")) o.getLong("reminderAt") else null,
                    position = o.optLong("position", o.optLong("updatedAt", System.currentTimeMillis())),
                    remindPings = o.optInt("remindPings", 1),
                    remindGapMin = o.optInt("remindGapMin", 5),
                    remindAlarm = o.optBoolean("remindAlarm", false),
                    remindRepeat = o.optInt("remindRepeat", 0),
                    remindAnchor = if (o.has("remindAnchor")) o.getLong("remindAnchor") else null,
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
                val images = o.optJSONArray("images") ?: JSONArray()
                val already = dao.attachmentsFor(noteId).map { it.fileName }.toSet()
                for (j in 0 until images.length()) {
                    val name = File(images.getString(j)).name
                    if (zip == null || name in already) continue
                    val entry = zip.getEntry("images/$name") ?: continue
                    if (!Images.file(context, name).exists()) {
                        zip.getInputStream(entry).use { Images.save(context, it, name) }
                    }
                    dao.insertAttachment(Attachment(noteId = noteId, fileName = name, createdAt = System.currentTimeMillis() + j))
                }
            }
        }
        return ImportResult(added, updated, skipped)
    }

    suspend fun ensureLabel(dao: NotesDao, name: String): Long =
        dao.labelByName(name)?.id ?: dao.insertLabel(Label(name = name))
}

