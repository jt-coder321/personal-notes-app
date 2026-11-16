package com.jt.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions

/** Reads the text out of a picture with on-device ML Kit text recognition. */
object Ocr {
    private const val MAX_PX = 1600

    /** Loads a picked picture scaled down and turned upright, ready for recognition. */
    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val degrees = try {
                val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (degrees == 0f) raw else {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Runs recognition on an upright bitmap. [onResult] gets the recognized
     * text, blank when no text was found, or null when recognition failed
     * (for example before the model has finished downloading).
     */
    fun recognize(bitmap: Bitmap, onResult: (String?) -> Unit) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText -> onResult(visionText.text) }
                .addOnFailureListener { onResult(null) }
        } catch (e: Exception) {
            onResult(null)
        }
    }
}
