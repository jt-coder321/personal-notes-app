package com.jt.notes.data

/** A [[Note title]] link: the raw match and the title it points at. */
private val LINK = Regex("\\[\\[([^\\[\\]]+)\\]\\]")

/** Every [[title]] written in a body, in order. Titles are trimmed; empty ones are dropped. */
fun extractLinkTitles(body: String): List<String> =
    LINK.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

/**
 * The note a [[title]] opens: the most recently updated note with exactly
 * that title, trash excluded. Links survive renames because the match is
 * made at render time, from the title as it is now; nothing is tracked by id.
 */
fun resolveLinkTitle(title: String, notes: List<Note>): Note? =
    notes.filter { it.trashedAt == null && it.title == title }.maxByOrNull { it.updatedAt }

/** Notes whose bodies link to [note] by its title. The note itself is excluded. */
fun backlinksFor(note: Note, notes: List<Note>): List<Note> {
    if (note.title.isBlank()) return emptyList()
    return notes.filter { other ->
        other.id != note.id && other.trashedAt == null &&
            extractLinkTitles(other.body).any { it == note.title }
    }.sortedByDescending { it.updatedAt }
}
