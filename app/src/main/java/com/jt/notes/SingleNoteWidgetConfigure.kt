package com.jt.notes

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jt.notes.data.Note
import com.jt.notes.data.NotesDb
import com.jt.notes.ui.LocalPalette
import com.jt.notes.ui.NotesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the one note a single-note widget shows. Launched by the widget host
 * when the widget is placed; backing out cancels the widget.
 */
class SingleNoteWidgetConfigure : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out cancels the widget.
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val themeId = getSharedPreferences("prefs", MODE_PRIVATE).getString("theme", "classic") ?: "classic"
        setContent {
            NotesTheme(themeId) {
                ConfigureScreen(onPick = { noteId ->
                    SingleNoteWidget.saveNoteId(this, appWidgetId, noteId)
                    // No update broadcast is sent when a widget has a
                    // configuration activity, so the first draw is requested here.
                    CoroutineScope(Dispatchers.IO).launch {
                        val manager = AppWidgetManager.getInstance(this@SingleNoteWidgetConfigure)
                        SingleNoteWidget.updateInstances(
                            this@SingleNoteWidgetConfigure,
                            manager,
                            intArrayOf(appWidgetId),
                        )
                        runOnUiThread {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                            )
                            finish()
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun ConfigureScreen(onPick: (Long) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val notes by produceState(initialValue = emptyList<Note>(), Unit) {
        value = withContext(Dispatchers.IO) {
            NotesDb.get(context).dao().allNotes()
                .filter { it.trashedAt == null && !it.archived }
                .sortedByDescending { it.updatedAt }
        }
    }
    Column(
        Modifier.fillMaxSize().background(p.ground).padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            "Choose a note",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = p.ink,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (notes.isEmpty()) {
            Text("No notes yet. Write one first, then add the widget.", color = p.muted)
        } else {
            LazyColumn {
                items(notes, key = { it.id }) { note ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(note.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            note.title.ifBlank { "Untitled note" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = p.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A locked note shows its title only, even here.
                        val firstLine = if (note.locked) "" else
                            note.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        if (firstLine.isNotEmpty()) {
                            Text(
                                firstLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = p.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
