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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    /** Text and icons on top of the accent color. */
    val onAccent: Color = Color.White,
    val cardRadius: Dp = 24.dp,
    val border: Dp = 0.dp,
    val borderColor: Color = Color.Transparent,
    val display: FontFamily? = null,
    val body: FontFamily? = null,
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

class ThemeSpec(val id: String, val name: String, val palette: Palette?)

/** The themes people can pick. "Classic" follows the phone's light and dark setting. */
object Themes {
    private fun c(v: Long) = Color(v)
    val classicPreview = LightPalette
    val all: List<ThemeSpec> = listOf(
        ThemeSpec("classic", "Classic (follows phone)", null),
        ThemeSpec("aurora", "Aurora glass", Palette(c(0xFF11143A), c(0xFF1F2354), c(0xFFFFFFFF), c(0xFFB9C0E6), c(0xFF7DF9FF), c(0xFF2A2F6B), c(0xFFFFFFFF), c(0xFFB9C0E6), true,
            onAccent = c(0xFF0B1020), cardRadius = 24.dp, border = 1.dp, borderColor = c(0x33FFFFFF))),
        ThemeSpec("paper", "Paper and ink", Palette(c(0xFFF4EFE6), c(0xFFFBF8F1), c(0xFF1F2A44), c(0xFF6D6452), c(0xFF1F3A5F), c(0xFF1F3A5F), c(0xFFF4EFE6), c(0xFFC8D2E4), false,
            cardRadius = 8.dp, display = FontFamily.Serif, body = FontFamily.Serif)),
        ThemeSpec("brutalist", "Neo-brutalist", Palette(c(0xFFFFF4D6), c(0xFFFFFFFF), c(0xFF000000), c(0xFF333333), c(0xFFFF6B6B), c(0xFF000000), c(0xFFFFFFFF), c(0xFFFFD93D), false,
            onAccent = c(0xFF000000), cardRadius = 14.dp, border = 3.dp, borderColor = c(0xFF000000))),
        ThemeSpec("bento", "Bento tiles", Palette(c(0xFFEEF1F6), c(0xFFFFFFFF), c(0xFF14171F), c(0xFF5F6673), c(0xFF14171F), c(0xFF14171F), c(0xFFFFFFFF), c(0xFF9CA3AF), false,
            cardRadius = 28.dp)),
        ThemeSpec("terminal", "Terminal", Palette(c(0xFF0D1117), c(0xFF161B22), c(0xFFE6EDF3), c(0xFF9AA4AF), c(0xFF3FB950), c(0xFF161B22), c(0xFFE6EDF3), c(0xFF9AA4AF), true,
            onAccent = c(0xFF0D1117), cardRadius = 8.dp, border = 1.dp, borderColor = c(0xFF30363D), display = FontFamily.Monospace, body = FontFamily.Monospace)),
        ThemeSpec("sunrise", "Sunrise", Palette(c(0xFFFFF6F4), c(0xFFFFFFFF), c(0xFF2B2330), c(0xFF7A6F83), c(0xFFE63E6D), c(0xFF2B2330), c(0xFFFFFFFF), c(0xFFD9CFE0), false,
            cardRadius = 22.dp)),
        ThemeSpec("swiss", "Swiss type", Palette(c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFF111111), c(0xFF555555), c(0xFFE4002B), c(0xFF111111), c(0xFFFFFFFF), c(0xFFC9C9C9), false,
            cardRadius = 0.dp, border = 1.dp, borderColor = c(0xFF111111))),
        ThemeSpec("board", "Sticky board", Palette(c(0xFFC9A27A), c(0xFFFFF8D6), c(0xFF2A2118), c(0xFF4F4030), c(0xFFB03A2E), c(0xFF2A2118), c(0xFFF6E7D3), c(0xFFD9C3A5), false,
            cardRadius = 4.dp)),
        ThemeSpec("timeline", "Violet", Palette(c(0xFFF6F5FB), c(0xFFFFFFFF), c(0xFF14102E), c(0xFF6A6682), c(0xFF6C4CF1), c(0xFF1B1740), c(0xFFFFFFFF), c(0xFFC9C4EE), false,
            cardRadius = 18.dp)),
        ThemeSpec("focus", "Focus", Palette(c(0xFF111114), c(0xFF1E1E25), c(0xFFFFFFFF), c(0xFF9B9BA6), c(0xFFFFE27A), c(0xFF26262F), c(0xFFFFFFFF), c(0xFFB8B8C2), true,
            onAccent = c(0xFF1B1B20), cardRadius = 32.dp)),
    )

    fun find(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: all[0]
}

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

private fun appTypography(displayFamily: FontFamily, bodyFamily: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = bodyFamily)
    fun TextStyle.display() = copy(fontFamily = displayFamily)
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
fun NotesTheme(themeId: String = "classic", content: @Composable () -> Unit) {
    val spec = Themes.find(themeId)
    val system = isSystemInDarkTheme()
    val chosen = spec.palette
    val p = chosen ?: if (system) DarkPalette else LightPalette
    val scheme = when {
        chosen != null -> {
            val base = if (chosen.dark) darkColorScheme() else lightColorScheme()
            base.copy(
                primary = chosen.accent,
                onPrimary = chosen.onAccent,
                background = chosen.ground,
                onBackground = chosen.ink,
                surface = chosen.surface,
                onSurface = chosen.ink,
                onSurfaceVariant = chosen.muted,
                surfaceContainer = chosen.surface,
                surfaceContainerHigh = chosen.surface,
                surfaceContainerHighest = chosen.ink.copy(alpha = 0.08f).compositeOver(chosen.surface),
                outlineVariant = chosen.ink.copy(alpha = 0.18f).compositeOver(chosen.surface),
            )
        }
        p.dark -> darkColorScheme(
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
        else -> lightColorScheme(
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
        MaterialTheme(colorScheme = scheme, typography = appTypography(p.display ?: Display, p.body ?: Body), content = content)
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
