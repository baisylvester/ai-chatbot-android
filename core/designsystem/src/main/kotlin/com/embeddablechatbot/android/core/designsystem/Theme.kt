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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Blue (primary), mint blue (secondary) and mint green (tertiary). Text/container pairs keep at least
 * 4.5:1 contrast in both themes. Warnings use their own amber (see [StatusColors]), never mint green.
 */
private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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

/** Soft tints for sender avatars: blue, mint blue and mint green, readable with [AvatarInk]. */
val AvatarTints = listOf(Color(0xFFDCE7FF), Color(0xFFC9F3F7), Color(0xFFC6F4DE), Color(0xFFE3EEFF), Color(0xFFD5F6EC))
val AvatarInk = Color(0xFF0B2E46)

@Composable
fun ChatbotTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Status colours that keep contrast in both themes. */
object StatusColors {
    @Composable fun warningContainer() = if (isSystemInDarkTheme()) Color(0xFF5A4200) else Color(0xFFFFE6AD)
    @Composable fun onWarningContainer() = if (isSystemInDarkTheme()) Color(0xFFFFE08A) else Color(0xFF3D2A00)
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
