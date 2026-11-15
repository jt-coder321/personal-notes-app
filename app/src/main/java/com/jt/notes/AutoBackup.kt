package com.jt.notes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jt.notes.data.Backup
import com.jt.notes.data.NotesDb
import java.util.concurrent.TimeUnit

/**
 * Once a day, writes a full backup to the folder the user picked.
 * It keeps one file, notes-auto-backup.zip, and replaces it each time.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val folder = prefs.getString(AutoBackup.PREF_FOLDER, null) ?: return Result.success()
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: return Result.failure()
            tree.findFile(AutoBackup.FILE_NAME)?.delete()
            val file = tree.createFile("application/zip", AutoBackup.FILE_NAME) ?: return Result.retry()
            val out = context.contentResolver.openOutputStream(file.uri) ?: return Result.retry()
            out.use { Backup.export(context, NotesDb.get(context).dao(), it, null) }
            prefs.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Throwable) {
            Result.retry()
        }
    }
}

object AutoBackup {
    const val PREF_FOLDER = "autoBackupFolder"
    const val FILE_NAME = "notes-auto-backup.zip"
    private const val DAILY = "auto-backup-daily"
    private const val NOW = "auto-backup-now"

    fun enable(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build(),
        )
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY)
    }
}
