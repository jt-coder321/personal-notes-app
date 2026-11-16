# Personal Notes

A private, offline-first notes app for Android, built for daily use on the
Samsung Galaxy Fold. No account, no cloud, no ads. Notes live on the device,
in a local database, with file backup and restore for safekeeping.

## Notes

- Rich text: bold, italic, strikethrough, underline, inline code, headings,
  and block quotes, with no visible symbols while typing.
- Checklist mode with sub-items, one-tap conversion between text and
  checklist, and interactive checking from the widget.
- Pin, archive, colors, duplicate, and trash with 30-day retention.
- Version history with one-tap restore of an earlier version.
- Search across titles, bodies, and labels, plus find-in-note with match
  navigation inside the editor.
- Templates: save any note as a reusable starter, then start new notes from it.
- Sketch canvas with stylus pressure, colors, and widths; sketches save with
  the note's pictures.
- Voice notes recorded in the editor, with playback and delete.
- Image attachments, speech-to-text, and receiving shared text from other apps.
- Per-note Markdown and PDF export, and share as plain text.

## Reminders

- Exact-time reminders with repeating pings, an alarm sound, snooze, and done.
- Daily and weekly repeats, with snooze affecting only the current occurrence.
- Full-screen alarm with volume that rises over about 30 seconds.
- A reminders list showing every upcoming reminder in one place.
- Location reminders: ping on arriving at or leaving a place, checked about
  every 15 minutes while location is on.

## Organization

- Labels, including nested labels: filtering by a parent includes its children.
- Sort by last edited, date created, title, or My order, with drag-to-arrange
  (hold a note until the notes jiggle, lift, then hold and drag).
- Filter pills, ten themes, and a two-pane list-plus-editor layout on wide
  screens such as the unfolded Fold.

## Widgets

- Quick capture, recent notes, interactive checklist, compact notes, and large
  recent notes.
- Every widget resizes down to 2 by 1 and adapts its content to its size,
  showing the notes themselves rather than just buttons.

## Backup and privacy

- Back up to a file, restore from a file, and automatic backup to a folder.
- Backup files can be password-encrypted.
- Import from Google Keep.
- Whole-app biometric lock.
- Everything stays on the device unless you export it.

## Building

Requirements: Android 8.0 (API 26) or later, JDK 17.

```sh
./gradlew assembleRelease
```

The `VERSION` file at the repo root drives the version name. Each release
bumps it by one number (1.009, 1.010, 1.011, ...). Pushing to `main` builds
and publishes a versioned release and updates the `latest` release; pushing
any other branch builds and tests without publishing.

## Status

Actively developed. Cloud sync (WebDAV first) and in-app updates from GitHub
releases are planned.
