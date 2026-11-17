package com.jt.notes.data

import android.content.Context

/**
 * The "What's new" card: shown once, on the first launch after an update,
 * saying what changed in the new version, then never again for that
 * version. A version with no entry below shows no card; a future version
 * adds one entry here.
 *
 * The card appears when the stored last-seen version differs from the
 * running version. When no version was ever stored, it appears only when
 * the package was updated before (lastUpdateTime after firstInstallTime),
 * which is what makes it show for the upgrade from a version that never
 * stored anything, while a genuinely fresh install never sees it.
 */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY_LAST_SEEN_VERSION = "last_seen_version"

    /** One short line per change, keyed by the version that introduced it. */
    private val notesByVersion = mapOf(
        "1.014" to listOf(
            "Note menu: 4 tidy groups instead of one long list.",
            "Top bar: the app icon replaces the big Notes title.",
            "Colors and formatting buttons: all visible, no more sideways scrolling.",
            "This card now appears after each update.",
        ),
    )

    /** The running version and its notes, or null when no card is due. */
    fun pendingNotes(context: Context): Pair<String, List<String>>? {
        val current = UpdateCheck.currentVersion(context)
        if (current.isEmpty()) return null
        val notes = notesByVersion[current] ?: return null
        val stored = prefs(context).getString(KEY_LAST_SEEN_VERSION, null)
        if (stored != null) {
            if (stored == current) return null
            return current to notes
        }
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return null
        if (info.lastUpdateTime <= info.firstInstallTime) return null
        return current to notes
    }

    /** Marks the running version as seen, so the card shows at most once per version. */
    fun markSeen(context: Context) {
        val current = UpdateCheck.currentVersion(context)
        if (current.isEmpty()) return
        prefs(context).edit().putString(KEY_LAST_SEEN_VERSION, current).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
