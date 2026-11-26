package com.embeddablechatbot.android.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.embeddablechatbot.android.core.domain.model.ThemeChoice
import com.embeddablechatbot.android.core.domain.model.ThemeMode
import com.embeddablechatbot.android.core.domain.model.ThemePalette

/**
 * Blue (primary), mint blue (secondary) and mint green (tertiary): the default palette. Text/container
 * pairs keep at least 4.5:1 contrast in both themes. Warnings use their own amber (see [StatusColors]).
 */
private val BlueMintLight = lightColorScheme(
    primary = Color(0xFF1E5FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF00707F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F3F7),
    onSecondaryContainer = Color(0xFF003C44),
    tertiary = Color(0xFF17795A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6F4DE),
    onTertiaryContainer = Color(0xFF00391F),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF5FAFD),
    onBackground = Color(0xFF151C21),
    surface = Color(0xFFFAFDFF),
    onSurface = Color(0xFF151C21),
    surfaceVariant = Color(0xFFDDE8EF),
    onSurfaceVariant = Color(0xFF41505A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF6FA),
    surfaceContainer = Color(0xFFE8F2F7),
    surfaceContainerHigh = Color(0xFFE2EDF3),
    surfaceContainerHighest = Color(0xFFDCE7EE),
    outline = Color(0xFF6F7E88),
    outlineVariant = Color(0xFFC1CFD8),
)

private val BlueMintDark = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF1846A6),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF7ED8E4),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F59),
    onSecondaryContainer = Color(0xFFC9F3F7),
    tertiary = Color(0xFF8ADBB5),
    onTertiary = Color(0xFF003822),
    tertiaryContainer = Color(0xFF005236),
    onTertiaryContainer = Color(0xFFC6F4DE),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0E1418),
    onBackground = Color(0xFFDDE4E9),
    surface = Color(0xFF0E1418),
    onSurface = Color(0xFFDDE4E9),
    surfaceVariant = Color(0xFF3F4A51),
    onSurfaceVariant = Color(0xFFBFC9D0),
    surfaceContainerLowest = Color(0xFF091013),
    surfaceContainerLow = Color(0xFF151D22),
    surfaceContainer = Color(0xFF19232A),
    surfaceContainerHigh = Color(0xFF222D34),
    surfaceContainerHighest = Color(0xFF2D383F),
    outline = Color(0xFF89949B),
    outlineVariant = Color(0xFF3F4A51),
)

/** Accent colors of one palette in one mode; neutral surfaces are tinted from [tint]. */
private class Accents(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val tint: Long,
)

private fun lightScheme(a: Accents): ColorScheme {
    val tint = Color(a.tint)
    fun neutral(f: Float) = lerp(Color.White, tint, f)
    return lightColorScheme(
        primary = Color(a.primary), onPrimary = Color(a.onPrimary), primaryContainer = Color(a.primaryContainer), onPrimaryContainer = Color(a.onPrimaryContainer),
        secondary = Color(a.secondary), onSecondary = Color(a.onSecondary), secondaryContainer = Color(a.secondaryContainer), onSecondaryContainer = Color(a.onSecondaryContainer),
        tertiary = Color(a.tertiary), onTertiary = Color(a.onTertiary), tertiaryContainer = Color(a.tertiaryContainer), onTertiaryContainer = Color(a.onTertiaryContainer),
        error = Color(0xFFBA1A1A),
        background = neutral(0.35f), onBackground = Color(0xFF1A1C1E),
        surface = neutral(0.2f), onSurface = Color(0xFF1A1C1E),
        surfaceVariant = neutral(1f), onSurfaceVariant = lerp(Color(0xFF44474E), Color(a.primary), 0.12f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = neutral(0.55f), surfaceContainer = neutral(0.7f),
        surfaceContainerHigh = neutral(0.85f), surfaceContainerHighest = neutral(1f),
        outline = lerp(Color(0xFF74777F), Color(a.primary), 0.12f), outlineVariant = lerp(Color(0xFFC4C6CF), tint, 0.4f),
    )
}

private fun darkScheme(a: Accents): ColorScheme {
    val tint = Color(a.tint)
    fun neutral(f: Float) = lerp(Color(0xFF111316), tint, f)
    return darkColorScheme(
        primary = Color(a.primary), onPrimary = Color(a.onPrimary), primaryContainer = Color(a.primaryContainer), onPrimaryContainer = Color(a.onPrimaryContainer),
        secondary = Color(a.secondary), onSecondary = Color(a.onSecondary), secondaryContainer = Color(a.secondaryContainer), onSecondaryContainer = Color(a.onSecondaryContainer),
        tertiary = Color(a.tertiary), onTertiary = Color(a.onTertiary), tertiaryContainer = Color(a.tertiaryContainer), onTertiaryContainer = Color(a.onTertiaryContainer),
        error = Color(0xFFFFB4AB),
        background = neutral(0.25f), onBackground = Color(0xFFE2E2E6),
        surface = neutral(0.25f), onSurface = Color(0xFFE2E2E6),
        surfaceVariant = neutral(1f), onSurfaceVariant = Color(0xFFC6C6CE),
        surfaceContainerLowest = neutral(0.1f), surfaceContainerLow = neutral(0.45f), surfaceContainer = neutral(0.6f),
        surfaceContainerHigh = neutral(0.8f), surfaceContainerHighest = lerp(tint, Color.White, 0.08f),
        outline = Color(0xFF8E9099), outlineVariant = neutral(1f),
    )
}

private val palettes: Map<ThemePalette, Pair<ColorScheme, ColorScheme>> = mapOf(
    ThemePalette.BlueMint to (BlueMintLight to BlueMintDark),
    ThemePalette.Violet to (
        lightScheme(Accents(0xFF542BEB, 0xFFFFFFFF, 0xFFECE5FF, 0xFF230D73, 0xFF625B71, 0xFFFFFFFF, 0xFFE8DEF8, 0xFF1E192B, 0xFF7D5260, 0xFFFFFFFF, 0xFFFFD8E4, 0xFF31111D, 0xFFE9E1FF)) to
            darkScheme(Accents(0xFFCEBFFF, 0xFF2D126D, 0xFF3F25A8, 0xFFECE5FF, 0xFFCCC2DC, 0xFF332D41, 0xFF4A4458, 0xFFE8DEF8, 0xFFEFB8C8, 0xFF492532, 0xFF633B48, 0xFFFFD8E4, 0xFF2E2742))
        ),
    ThemePalette.Forest to (
        lightScheme(Accents(0xFF2E6B30, 0xFFFFFFFF, 0xFFC2EFB9, 0xFF002204, 0xFF52634F, 0xFFFFFFFF, 0xFFD5E8CF, 0xFF101F0F, 0xFF386569, 0xFFFFFFFF, 0xFFBCEBEF, 0xFF002023, 0xFFDDEEDA)) to
            darkScheme(Accents(0xFFA7D79E, 0xFF00390B, 0xFF14521B, 0xFFC2EFB9, 0xFFB9CCB4, 0xFF253423, 0xFF3B4B38, 0xFFD5E8CF, 0xFFA0CFD3, 0xFF00363A, 0xFF1E4D51, 0xFFBCEBEF, 0xFF1F2A1E))
        ),
    ThemePalette.Sunset to (
        lightScheme(Accents(0xFFB3261E, 0xFFFFFFFF, 0xFFFFDAD5, 0xFF410001, 0xFF8B5000, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2C1600, 0xFF6D5E00, 0xFFFFFFFF, 0xFFF8E287, 0xFF211B00, 0xFFFFE4DE)) to
            darkScheme(Accents(0xFFFFB4AB, 0xFF690003, 0xFF8F0E0B, 0xFFFFDAD5, 0xFFFFB870, 0xFF4A2800, 0xFF693C00, 0xFFFFDCBE, 0xFFDBC66E, 0xFF3A3000, 0xFF534600, 0xFFF8E287, 0xFF2F201E))
        ),
    ThemePalette.Rose to (
        lightScheme(Accents(0xFF9C2C5B, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001C, 0xFF7B5263, 0xFFFFFFFF, 0xFFFFD8E8, 0xFF30111F, 0xFF7C5635, 0xFFFFFFFF, 0xFFFFDCC1, 0xFF2E1500, 0xFFFFE3EA)) to
            darkScheme(Accents(0xFFFFB1C8, 0xFF5E1131, 0xFF7E1544, 0xFFFFD9E2, 0xFFE3BDCB, 0xFF452934, 0xFF5E3F4B, 0xFFFFD8E8, 0xFFEEBD93, 0xFF47290C, 0xFF613F20, 0xFFFFDCC1, 0xFF2F1F25))
        ),
    ThemePalette.Graphite to (
        lightScheme(Accents(0xFF44546A, 0xFFFFFFFF, 0xFFD4E3FA, 0xFF0B1D2F, 0xFF545F70, 0xFFFFFFFF, 0xFFD8E3F8, 0xFF111C2B, 0xFF6B5778, 0xFFFFFFFF, 0xFFF2DAFF, 0xFF251431, 0xFFE4E7EC)) to
            darkScheme(Accents(0xFFB9C8E0, 0xFF233144, 0xFF3A4A5F, 0xFFD4E3FA, 0xFFBCC7DB, 0xFF263141, 0xFF3C4858, 0xFFD8E3F8, 0xFFD7BEE4, 0xFF3B2948, 0xFF523F5F, 0xFFF2DAFF, 0xFF24272C))
        ),
)

/** Whether the app is drawing its dark scheme (which can differ from the system setting). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isAppInDarkTheme(choice: ThemeChoice): Boolean = when (choice.mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** The color scheme for [palette] in light or dark; [ThemePalette.Device] needs Android 12+, else the default. */
@Composable
fun colorSchemeFor(palette: ThemePalette, dark: Boolean): ColorScheme {
    if (palette == ThemePalette.Device && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val (light, darkScheme) = palettes[palette] ?: palettes.getValue(ThemePalette.BlueMint)
    return if (dark) darkScheme else light
}

/** Whether "Match my wallpaper" can be offered on this phone. */
val deviceColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun ChatbotTheme(choice: ThemeChoice = ThemeChoice(), content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(choice)
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colorSchemeFor(choice.palette, dark), content = content)
    }
}

/** Sender avatar colors from the current palette: primary, secondary and tertiary containers. */
@Composable
fun avatarColors(seed: String): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val options = listOf(c.primaryContainer to c.onPrimaryContainer, c.secondaryContainer to c.onSecondaryContainer, c.tertiaryContainer to c.onTertiaryContainer)
    return options[(seed.hashCode() and Int.MAX_VALUE) % options.size]
}

/** Status colours that keep contrast in both themes. */
object StatusColors {
    @Composable fun warningContainer() = if (LocalDarkTheme.current) Color(0xFF5A4200) else Color(0xFFFFE6AD)
    @Composable fun onWarningContainer() = if (LocalDarkTheme.current) Color(0xFFFFE08A) else Color(0xFF3D2A00)
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(40.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

enum class BannerTone { Info, Warning, Error }

/** Announced politely by TalkBack when it appears or changes. */
@Composable
fun StatusBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Warning -> StatusColors.warningContainer() to StatusColors.onWarningContainer()
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(title = message, body = "", modifier = modifier) {
        Button(onClick = onRetry) { Text(retryLabel) }
    }
}
