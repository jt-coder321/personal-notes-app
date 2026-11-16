package com.jt.notes.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updates from the public GitHub repo. On launch the app asks
 * api.github.com for the latest release on main; when it carries a newer
 * APK than the running version, a dialog offers to download it and hand
 * it to Android's installer. No new dependencies: the release JSON is
 * read with a small targeted scan, and downloads use plain
 * HttpURLConnection, following the LinkPreviews fetcher pattern.
 *
 * Automatic checks run at most once every 24 hours and stay silent when
 * there is no update or the check fails. Tapping the Version row in the
 * menu checks right away and always says what it found.
 */
object UpdateCheck {
    /** A newer release: its version (no leading "v") and APK download link. */
    data class Update(val version: String, val downloadUrl: String)

    /** What a check the person asked for found. */
    sealed interface ManualResult {
        data class Available(val update: Update) : ManualResult
        object UpToDate : ManualResult
        object Failed : ManualResult
    }

    private sealed interface CheckOutcome {
        data class Newer(val update: Update) : CheckOutcome
        object UpToDate : CheckOutcome
        object Failed : CheckOutcome
    }

    private const val RELEASES_URL =
        "https://api.github.com/repos/jt-coder321/personal-notes-app/releases/latest"
    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val CHECK_CONNECT_TIMEOUT_MS = 10_000
    private const val CHECK_READ_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_RELEASE_JSON_BYTES = 256 * 1024
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val APK_NAME_PREFIX = "personal-notes-v"
    private const val APK_NAME_SUFFIX = ".apk"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val FILE_PROVIDER_AUTHORITY = "com.jt.notes.files"

    /** The running app's version name, "" when it cannot be read. */
    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    /**
     * Compares dotted versions segment by segment, numerically: 1.010 beats
     * 1.009, and a missing segment counts as 0. A leading "v" is ignored,
     * and a segment that is not a number counts as 0.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.trim().removePrefix("v").split(".")
        val right = b.trim().removePrefix("v").split(".")
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /** True when an automatic check may run: 24 hours since the last attempt. */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /**
     * The launch check: silent and throttled. Returns the update to offer,
     * or null when no check ran, nothing is newer, the check failed, or the
     * version on offer was already dismissed. Every attempt counts toward
     * the throttle, including failed ones, so a dead network does not spin.
     */
    suspend fun autoCheck(context: Context): Update? {
        if (!shouldAutoCheck(context)) return null
        val outcome = check(context)
        recordCheck(context)
        val update = (outcome as? CheckOutcome.Newer)?.update ?: return null
        if (dismissedVersion(context) == update.version) return null
        return update
    }

    /** A check the person asked for: never throttled, never hidden by a dismissal, and always reports what it found. */
    suspend fun manualCheck(context: Context): ManualResult {
        val outcome = check(context)
        recordCheck(context)
        return when (outcome) {
            is CheckOutcome.Newer -> ManualResult.Available(outcome.update)
            CheckOutcome.UpToDate -> ManualResult.UpToDate
            CheckOutcome.Failed -> ManualResult.Failed
        }
    }

    /** Remembers a dismissed version, so the prompt does not nag until a newer one appears. */
    fun dismissVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    /**
     * Downloads the release APK into the cache updates folder, which the
     * FileProvider serves. Returns the file, or null on any failure; a
     * partial download is deleted, never left behind to install.
     */
    suspend fun download(context: Context, update: Update): File? = withContext(Dispatchers.IO) {
        runCatching { downloadBlocking(context, update) }.getOrNull()
    }

    /**
     * Hands the downloaded APK to Android's installer. Android always asks
     * before installing, and asks once to allow installs from this app;
     * both prompts are the system doing its job. False when no installer
     * would take the file.
     */
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun recordCheck(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    private fun dismissedVersion(context: Context): String? =
        prefs(context).getString(KEY_DISMISSED_VERSION, null)

    private suspend fun check(context: Context): CheckOutcome = withContext(Dispatchers.IO) {
        val json = try {
            fetchReleaseJson()
        } catch (e: Exception) {
            return@withContext CheckOutcome.Failed
        } ?: return@withContext CheckOutcome.Failed
        val release = parseLatestRelease(json) ?: return@withContext CheckOutcome.Failed
        if (compareVersions(release.version, currentVersion(context)) > 0) {
            CheckOutcome.Newer(release)
        } else {
            CheckOutcome.UpToDate
        }
    }

    private fun fetchReleaseJson(): String? {
        val connection = open(RELEASES_URL, CHECK_CONNECT_TIMEOUT_MS, CHECK_READ_TIMEOUT_MS)
        return try {
            if (connection.responseCode !in 200..299) null
            else {
                val bytes = readUpTo(connection, MAX_RELEASE_JSON_BYTES)
                if (bytes.size > MAX_RELEASE_JSON_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBlocking(context: Context, update: Update): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "personal-notes-${update.version}.apk")
        if (file.exists()) file.delete()
        val downloaded = runCatching { downloadInto(update.downloadUrl, file) }.getOrDefault(false)
        if (!downloaded) {
            file.delete()
            return null
        }
        return file
    }

    /** Streams the URL into [file]. False when the response, the size, or the stream is wrong. */
    private fun downloadInto(url: String, file: File): Boolean {
        val connection = open(url, DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
        try {
            if (connection.responseCode !in 200..299) return false
            val expected = connection.contentLengthLong
            var total = 0L
            file.outputStream().buffered().use { out ->
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) return false
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) return false
            if (expected >= 0 && total != expected) return false
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Notes/1.0")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** Reads at most [maxBytes] + 1 bytes, so an oversized body is noticed, not swallowed whole. */
    private fun readUpTo(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        connection.inputStream.use { stream ->
            while (total <= maxBytes) {
                val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray()
    }

    /**
     * Reads the version and the .apk download link out of a releases/latest
     * response. Null when either is missing or the JSON is not shaped as
     * expected. The version comes from the asset name
     * (personal-notes-v1.012.apk), because the workflow's "latest" release
     * carries the tag "latest", not a version; a numeric tag is the
     * fallback when an asset name is not shaped that way.
     */
    internal fun parseLatestRelease(json: String): Update? {
        val tag = fieldValue(json, "tag_name")?.trim()?.takeIf { it.isNotEmpty() }
        val assets = arrayObjects(json, "assets") ?: return null
        for (asset in assets) {
            val name = fieldValue(asset, "name") ?: continue
            if (!name.endsWith(APK_NAME_SUFFIX)) continue
            val url = fieldValue(asset, "browser_download_url") ?: continue
            val version = versionFromAssetName(name)
                ?: tag?.removePrefix("v")?.takeIf { isVersionLike(it) }
                ?: continue
            return Update(version, url)
        }
        return null
    }

    /** The version carried by a release asset name like personal-notes-v1.012.apk. Null when the name is not shaped that way. */
    internal fun versionFromAssetName(name: String): String? {
        if (!name.startsWith(APK_NAME_PREFIX) || !name.endsWith(APK_NAME_SUFFIX)) return null
        return name.removePrefix(APK_NAME_PREFIX).removeSuffix(APK_NAME_SUFFIX).takeIf { isVersionLike(it) }
    }

    private fun isVersionLike(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isDigit() || it == '.' }

    /** The first string value of the named field in a JSON object. Null when the field is missing or empty. */
    private fun fieldValue(obj: String, field: String): String? {
        val pattern = "\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        return Regex(pattern).find(obj)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotEmpty() }
    }

    /** Undoes the JSON escapes that can appear in a tag name or a download URL. */
    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")

    /** The top-level objects of the named JSON array, in order. Null when the field or array is missing or unbalanced. */
    private fun arrayObjects(json: String, field: String): List<String>? {
        val keyAt = json.indexOf("\"$field\"")
        if (keyAt < 0) return null
        val openAt = json.indexOf('[', keyAt)
        if (openAt < 0) return null
        val closeAt = matchingBracket(json, openAt, '[', ']') ?: return null
        val inner = json.substring(openAt + 1, closeAt)
        val objects = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            val start = inner.indexOf('{', i)
            if (start < 0) break
            val end = matchingBracket(inner, start, '{', '}') ?: return null
            objects.add(inner.substring(start, end + 1))
            i = end + 1
        }
        return objects
    }

    /** The index of the bracket matching the one at [openAt], skipping over JSON strings. Null when unbalanced. */
    private fun matchingBracket(text: String, openAt: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openAt until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
