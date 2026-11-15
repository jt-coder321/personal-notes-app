package com.jt.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Pictures attached to notes: stored as files in the app's private folder. */
object Images {
    private val cache = LruCache<String, Bitmap>(24)

    fun dir(context: Context): File = File(context.filesDir, "images").apply { mkdirs() }

    /** Only the last path part is used, so a name can never point outside the folder. */
    fun file(context: Context, name: String): File = File(dir(context), File(name).name)

    fun newName(): String = UUID.randomUUID().toString() + ".jpg"

    /** Copies a picked picture into the app. Returns the new file name, or null on failure. */
    fun importFrom(context: Context, uri: Uri): String? = try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { save(context, it) }
    } catch (e: Exception) {
        null
    }

    fun save(context: Context, input: InputStream, name: String = newName()): String {
        file(context, name).outputStream().use { input.copyTo(it) }
        return File(name).name
    }

    /** Loads a picture scaled down to roughly [maxPx] on its longer side, upright. */
    fun load(context: Context, name: String, maxPx: Int): Bitmap? {
        val key = "$name@$maxPx"
        cache.get(key)?.let { return it }
        val file = file(context, name)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(file.path, options) ?: return null
            val degrees = when (
                ExifInterface(file.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val upright = if (degrees == 0f) raw else {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
            }
            cache.put(key, upright)
            upright
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes picture files that no note uses any more. */
    fun deleteUnused(context: Context, used: Set<String>) {
        // Files from the last hour are left alone: a photo may still be on its way in.
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        dir(context).listFiles()?.forEach {
            if (it.name !in used && it.lastModified() < cutoff) it.delete()
        }
    }
}
