package com.jt.notes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.jt.notes.R

/** The app's own colors: a quiet neutral surface with one bright action color. */
data class Palette(
    val ground: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val bar: Color,
    val onBar: Color,
    val onBarMuted: Color,
    val dark: Boolean,
)

private val LightPalette = Palette(
    ground = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF0E1116),
    muted = Color(0xFF4A5160),
    accent = Color(0xFF3B5BFF),
    bar = Color(0xFF0E1116),
    onBar = Color(0xFFFFFFFF),
    onBarMuted = Color(0xFFC9CED8),
    dark = false,
)

private val DarkPalette = Palette(
    ground = Color(0xFF0B0D12),
    surface = Color(0xFF171B24),
    ink = Color(0xFFF2F4F8),
    muted = Color(0xFFA3ABBA),
    accent = Color(0xFF3B5BFF),
    bar = Color(0xFF1D222D),
    onBar = Color(0xFFF2F4F8),
    onBarMuted = Color(0xFFC9CED8),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Space Grotesk, for titles. */
val Display = FontFamily(
    variable(R.font.space_grotesk, FontWeight.Medium),
    variable(R.font.space_grotesk, FontWeight.SemiBold),
    variable(R.font.space_grotesk, FontWeight.Bold),
)

/** Inter, for everything else. */
val Body = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
)

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = Body)
    fun TextStyle.display() = copy(fontFamily = Display)
    return Typography(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.display(),
        titleMedium = base.titleMedium.display(),
        titleSmall = base.titleSmall.display(),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(),
        labelMedium = base.labelMedium.body(),
        labelSmall = base.labelSmall.body(),
    )
}

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = Color(0xFF9DB0FF),
            onPrimary = Color(0xFF0B0D12),
            background = p.ground,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.surface,
            surfaceContainerHigh = Color(0xFF1D222D),
            surfaceContainerHighest = Color(0xFF262C38),
            outlineVariant = Color(0xFF2E3542),
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            background = p.ground,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            onSurfaceVariant = p.muted,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = Color(0xFFEEF0F4),
            outlineVariant = Color(0xFFD9DDE5),
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = appTypography(), content = content)
    }
}

/** Note background choices as (light theme, dark theme). Index 0 means "no color". */
val NoteColors: List<Pair<Color, Color>> = listOf(
    Color.Unspecified to Color.Unspecified,
    Color(0xFFFFE8A3) to Color(0xFF3A2F0B), // sun
    Color(0xFFCFEFD9) to Color(0xFF12301F), // mint
    Color(0xFFD6E4FF) to Color(0xFF16264A), // sky
    Color(0xFFFFD9E2) to Color(0xFF3D1422), // rose
    Color(0xFFFFDCC7) to Color(0xFF3D2010), // peach
    Color(0xFFE6DCFF) to Color(0xFF251A47), // lilac
    Color(0xFFC9EEF0) to Color(0xFF0F2E31), // teal
)

/** The background for a note color index, or null when the note has no color. */
@Composable
fun noteBackground(index: Int): Color? {
    if (index <= 0 || index >= NoteColors.size) return null
    val (light, dark) = NoteColors[index]
    return if (LocalPalette.current.dark) dark else light
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
        cursorBrush = SolidColor(LocalPalette.current.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = color.copy(alpha = 0.45f))
                }
                inner()
            }
        },
    )
}
