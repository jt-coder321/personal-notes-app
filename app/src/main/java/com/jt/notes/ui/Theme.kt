package com.jt.notes.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Note background choices as (light theme, dark theme). Index 0 means "no color". */
val NoteColors: List<Pair<Color, Color>> = listOf(
    Color.Unspecified to Color.Unspecified,
    Color(0xFFFAAFA8) to Color(0xFF77172E), // coral
    Color(0xFFF39F76) to Color(0xFF692B17), // peach
    Color(0xFFFFF8B8) to Color(0xFF7C4A03), // sand
    Color(0xFFE2F6D3) to Color(0xFF264D3B), // mint
    Color(0xFFB4DDD3) to Color(0xFF0C625D), // sage
    Color(0xFFD4E4ED) to Color(0xFF256377), // fog
    Color(0xFFAECCDC) to Color(0xFF284255), // storm
    Color(0xFFD3BFDB) to Color(0xFF472E5B), // dusk
    Color(0xFFF6E2DD) to Color(0xFF6C394F), // blossom
    Color(0xFFE9E3D4) to Color(0xFF4B443A), // clay
    Color(0xFFEFEFF1) to Color(0xFF232427), // chalk
)

/** The background for a note color index, or null when the note has no color. */
@Composable
fun noteBackground(index: Int): Color? {
    if (index <= 0 || index >= NoteColors.size) return null
    val (light, dark) = NoteColors[index]
    return if (isSystemInDarkTheme()) dark else light
}

/** A borderless text field with a placeholder, used for titles, bodies and checklist items. */
@Composable
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    color: Color = LocalContentColor.current,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = color),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = color.copy(alpha = 0.5f))
                }
                inner()
            }
        },
    )
}
