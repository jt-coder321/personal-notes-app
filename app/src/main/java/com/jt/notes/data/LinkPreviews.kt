package com.jt.notes.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A fetched page title plus its Open Graph image, if any. */
data class LinkPreview(val title: String, val image: Bitmap?)

/**
 * First-URL previews for note cards. Fetches use plain HttpURLConnection on
 * IO, with a 5 second connect and 5 second read timeout, reading at most
 * the first 64 KB of HTML (the head, where titles and Open Graph tags live).
 * Results live in memory only, at most 50 entries, so a restart refetches.
 * Any failure yields null and the card shows nothing. Plain http:// pages
 * fail under the default no-cleartext policy and simply get no preview.
 */
object LinkPreviews {
    private const val MAX_HTML_BYTES = 64 * 1024
    private const val MAX_IMAGE_BYTES = 512 * 1024
    private const val MAX_IMAGE_PX = 600
    private const val MAX_ENTRIES = 50
    private const val TIMEOUT_MS = 5_000

    private val cache = object : LinkedHashMap<String, LinkPreview?>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreview?>) =
            size > MAX_ENTRIES
    }
    private val lock = Mutex()

    /** The first web address in a body, or null. */
    fun firstUrl(body: String): String? =
        Patterns.WEB_URL.toRegex().find(body)?.value?.takeIf { it.contains('.') }

    /** The cached preview, or a fresh fetch. Failures are cached too, so a bad URL is fetched once. */
    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        lock.withLock { if (cache.containsKey(url)) return@withContext cache[url] }
        val preview = runCatching { load(url) }.getOrNull()
        lock.withLock { cache[url] = preview }
        preview
    }

    private fun load(rawUrl: String): LinkPreview? {
        val url = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
        val html = readText(url, MAX_HTML_BYTES) ?: return null
        val head = html.substringBefore("</head>", html)
        val title = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(head)?.let { contentOf(it.value) }
            ?: Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(head)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val imageUrl = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(head)?.let { contentOf(it.value) }
        val image = imageUrl?.let { runCatching { loadImage(resolve(url, it)) }.getOrNull() }
        return LinkPreview(unescape(title), image)
    }

    private fun contentOf(tag: String): String? =
        Regex("content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolve(base: String, ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> {
            val u = URL(base)
            "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$ref"
        }
        else -> base.substringBeforeLast('/') + "/" + ref
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Notes/1.0")
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

    private fun readText(url: String, maxBytes: Int): String? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            String(readUpTo(connection, maxBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun loadImage(url: String): Bitmap? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = readUpTo(connection, MAX_IMAGE_BYTES)
            if (bytes.size > MAX_IMAGE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun unescape(text: String): String =
        text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").trim()
}
