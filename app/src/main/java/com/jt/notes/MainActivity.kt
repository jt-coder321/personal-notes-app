package com.jt.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jt.notes.ui.EditorScreen
import com.jt.notes.ui.HomeScreen
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

    val id = editingId
    if (id != null) {
        EditorScreen(vm = vm, noteId = id, onClose = { editingId = null })
    } else {
        HomeScreen(
            vm = vm,
            gridState = gridState,
            onOpen = { editingId = it },
            onCreate = { isChecklist -> scope.launch { editingId = vm.createNote(isChecklist) } },
        )
    }
}
