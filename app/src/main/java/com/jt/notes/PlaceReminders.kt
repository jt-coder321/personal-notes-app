package com.jt.notes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jt.notes.data.NotesDb
import com.jt.notes.data.parseItems
import java.util.concurrent.TimeUnit

/**
 * Place reminders: a note pings when the phone arrives at, or leaves, a place.
 * A periodic worker checks at best every 15 minutes, using the last known
 * location, so no map and no extra service is needed. WorkManager keeps the
 * checker running across reboots on its own.
 */
class PlaceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val dao = NotesDb.get(app).dao()
        val reminders = try {
            dao.allPlaceReminders()
        } catch (e: Exception) {
            return Result.success()
        }
        if (reminders.isEmpty()) return Result.success()
        val here = PlaceReminders.lastKnown(app) ?: return Result.success()
        for (reminder in reminders) {
            if (reminder.fired) continue
            val note = dao.getNote(reminder.noteId) ?: continue
            if (note.trashedAt != null) {
                dao.deletePlaceReminder(reminder.noteId)
                continue
            }
            val inside = PlaceReminders.distanceM(
                here.latitude, here.longitude, reminder.latitude, reminder.longitude,
            ) <= reminder.radiusM
            val was = reminder.lastInside
            if (was == null) {
                // The first check only records the state; it never fires.
                dao.updatePlaceReminder(reminder.copy(lastInside = inside))
                continue
            }
            val fire = when (reminder.trigger) {
                PlaceReminders.TRIGGER_ARRIVE -> !was && inside
                PlaceReminders.TRIGGER_LEAVE -> was && !inside
                else -> false
            }
            if (fire) {
                val text = if (note.isChecklist) {
                    parseItems(note.body).filter { !it.checked && it.text.isNotBlank() }
                        .joinToString("\n") { it.text }
                } else {
                    note.body
                }
                Reminders.show(app, note, text.take(400))
                dao.updatePlaceReminder(reminder.copy(lastInside = inside, fired = true))
            } else if (was != inside) {
                dao.updatePlaceReminder(reminder.copy(lastInside = inside))
            }
        }
        return Result.success()
    }
}

object PlaceReminders {
    const val TRIGGER_ARRIVE = 0
    const val TRIGGER_LEAVE = 1

    const val RADIUS_DEFAULT = 250
    val RADII = listOf(100, 250, 500)

    private const val WORK_NAME = "place-reminder-check"

    /** Enqueues the 15-minute checker, if it is not already running. */
    fun scheduleWorker(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlaceReminderWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /** The freshest last-known location, or null when location is off or permission is missing. */
    fun lastKnown(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = fine || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!coarse) return null
        var best: Location? = null
        for (name in manager.getProviders(true)) {
            if (name == LocationManager.GPS_PROVIDER && !fine) continue
            val loc = try {
                manager.getLastKnownLocation(name)
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    /** Distance in metres between two points. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }
}
