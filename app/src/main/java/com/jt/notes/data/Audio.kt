package com.jt.notes.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import java.util.UUID

/** Voice recordings attached to notes: stored as .m4a files in the app's private folder. */
object AudioClips {
    fun dir(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }

    /** Only the last path part is used, so a name can never point outside the folder. */
    fun file(context: Context, name: String): File = File(dir(context), File(name).name)

    fun newName(): String = UUID.randomUUID().toString() + ".m4a"
}

/** Records one voice note to the app's audio folder. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputName: String? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds recorded so far, or 0 when not recording. */
    val elapsedMs: Long get() = if (recorder == null) 0L else System.currentTimeMillis() - startedAt

    /**
     * Starts recording. Uses the no-arg MediaRecorder constructor, which works
     * on every supported Android version (the Context constructor needs API 31).
     */
    @Suppress("DEPRECATION")
    fun start(): Boolean {
        return try {
            val name = AudioClips.newName()
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(AudioClips.file(context, name).absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputName = name
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            recorder?.runCatching { release() }
            recorder = null
            outputName = null
            false
        }
    }

    /** Stops and keeps the recording. Returns the file name and length, or null when nothing was kept. */
    fun stop(): Pair<String, Long>? {
        val rec = recorder ?: return null
        val name = outputName
        val duration = System.currentTimeMillis() - startedAt
        recorder = null
        outputName = null
        return try {
            rec.stop()
            rec.release()
            if (name != null) name to duration else null
        } catch (e: Exception) {
            runCatching { rec.release() }
            if (name != null) AudioClips.file(context, name).delete()
            null
        }
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val rec = recorder ?: return
        val name = outputName
        recorder = null
        outputName = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        if (name != null) AudioClips.file(context, name).delete()
    }
}

/** Plays one voice note at a time. */
class ClipPlayer {
    private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Plays [file] from the start, stopping anything already playing. [onFinished] runs when it ends. */
    fun play(file: File, onFinished: () -> Unit) {
        stop()
        try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setOnCompletionListener { onFinished() }
                prepare()
                start()
                player = this
            }
        } catch (e: Exception) {
            onFinished()
        }
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    fun resume() {
        runCatching { player?.start() }
    }

    /** Stops playback and releases the player. */
    fun stop() {
        player?.runCatching { stop() }
        player?.runCatching { release() }
        player = null
    }

    fun release() = stop()
}
