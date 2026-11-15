package com.jt.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jt.notes.ui.EditorScreen
import com.jt.notes.ui.HomeScreen
import com.jt.notes.ui.LocalPalette
import com.jt.notes.ui.NotesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTheme {
                App()
            }
        }
    }
}

@Composable
fun App(vm: NotesViewModel = viewModel()) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current

    val open: (Long) -> Unit = { editingId = it }
    val create: (Boolean) -> Unit = { isChecklist ->
        scope.launch { editingId = vm.createNote(isChecklist) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    ) {
        val id = editingId
        if (maxWidth >= 600.dp) {
            // Wide screens (the unfolded phone): list on the left, open note on the right.
            val listWidth = (maxWidth * 0.42f).coerceIn(300.dp, 380.dp)
            Row(Modifier.fillMaxSize()) {
                HomeScreen(
                    vm = vm,
                    gridState = gridState,
                    selectedId = id,
                    onOpen = open,
                    onCreate = create,
                    modifier = Modifier.width(listWidth),
                )
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (id != null) {
                        key(id) {
                            EditorScreen(
                                vm = vm,
                                noteId = id,
                                embedded = true,
                                onClose = { if (editingId == id) editingId = null },
                            )
                        }
                    } else {
                        Text(
                            "Pick a note, or start a new one",
                            style = MaterialTheme.typography.bodyLarge,
                            color = p.muted,
                        )
                    }
                }
            }
        } else if (id != null) {
            key(id) {
                EditorScreen(vm = vm, noteId = id, embedded = false, onClose = { editingId = null })
            }
        } else {
            HomeScreen(
                vm = vm,
                gridState = gridState,
                selectedId = null,
                onOpen = open,
                onCreate = create,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
