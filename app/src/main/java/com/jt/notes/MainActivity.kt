package com.jt.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jt.notes.ui.EditorScreen
import com.jt.notes.ui.HomeScreen
import com.jt.notes.ui.LocalPalette
import com.jt.notes.ui.NotesTheme

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_NEW_NOTE = "com.jt.notes.NEW_NOTE"
        const val ACTION_NEW_CHECKLIST = "com.jt.notes.NEW_CHECKLIST"
        const val ACTION_OPEN_NOTE = "com.jt.notes.OPEN_NOTE"

        /** Away from the app for longer than this, and the lock comes back. */
        private const val RELOCK_AFTER_MS = 60_000L

        /** True once the lock has been opened in this run of the app. */
        private var unlocked = false

        val LOCK_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    private val vm: NotesViewModel by viewModels()
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    private fun lockEnabled(): Boolean =
        getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("lock", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText(android.util.Log.getStackTraceString(e).take(6000)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        val lastCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        Reminders.ensureChannel(this)
        locked = lockEnabled() && !unlocked
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            NotesTheme {
                var crashText by rememberSaveable { mutableStateOf(lastCrash) }
                crashText?.let { text ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { crashText = null },
                        title = { Text("The app crashed last time") },
                        text = { Text(text.take(1500), style = MaterialTheme.typography.bodySmall) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                                crashText = null
                            }) { Text("Copy and close") }
                        },
                    )
                }
                if (locked) {
                    LockScreen(onUnlock = { promptUnlock() })
                } else {
                    App(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) stoppedAt = SystemClock.elapsedRealtime()
        RecentNotesWidget.refresh(this)
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && lockEnabled() && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) {
            unlocked = false
            locked = true
        }
        stoppedAt = 0L
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                if (text.isNotBlank() || subject.isNotBlank()) {
                    vm.createAndOpen(isChecklist = false, title = subject, body = text)
                }
            }
            ACTION_NEW_NOTE -> vm.createAndOpen(isChecklist = false)
            ACTION_NEW_CHECKLIST -> vm.createAndOpen(isChecklist = true)
            ACTION_OPEN_NOTE -> {
                val id = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1L)
                if (id > 0) vm.pendingOpen.value = id
            }
        }
    }

    private fun promptUnlock() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    locked = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Notes")
            .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(p.ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = p.ink, modifier = Modifier.size(40.dp))
            Text("Notes is locked", style = MaterialTheme.typography.titleLarge, color = p.ink)
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

@Composable
fun App(vm: NotesViewModel = viewModel()) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    val p = LocalPalette.current

    // Notes opened from outside the screen: a share, the widget, a reminder, or "+".
    val pendingOpen by vm.pendingOpen.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen) {
        val id = pendingOpen
        if (id != null) {
            editingId = id
            vm.pendingOpen.value = null
        }
    }

    val open: (Long) -> Unit = { editingId = it }

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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
