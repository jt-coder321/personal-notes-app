package com.jt.notes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jt.notes.data.Backup
import com.jt.notes.data.NotesDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Once a day, writes a full backup to the folder the user picked.
 * It keeps one file, notes-auto-backup.zip, and replaces it each time.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (prefs.getString(AutoBackup.PREF_FOLDER, null) == null) return Result.success()
        return if (AutoBackup.runNow(applicationContext) == null) Result.success() else Result.retry()
    }
}

object AutoBackup {
    const val PREF_FOLDER = "autoBackupFolder"
    const val FILE_NAME = "notes-auto-backup.zip"
    private const val DAILY = "auto-backup-daily"

    /** Writes the backup file to the chosen folder. Returns null on success, or a short error message. */
    suspend fun runNow(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val folder = prefs.getString(PREF_FOLDER, null) ?: return@withContext "No folder is chosen"
        try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
            if (tree == null || !tree.canWrite()) return@withContext "That folder is not writable. Pick another one."
            tree.findFile(FILE_NAME)?.delete()
            val file = tree.createFile("application/zip", FILE_NAME) ?: return@withContext "Could not create the file in that folder"
            val out = context.contentResolver.openOutputStream(file.uri) ?: return@withContext "Could not open the backup file"
            out.use { Backup.export(context, NotesDb.get(context).dao(), it, null) }
            prefs.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
            null
        } catch (e: Throwable) {
            e.message ?: "Backup failed"
        }
    }

    fun enable(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY)
    }
}
