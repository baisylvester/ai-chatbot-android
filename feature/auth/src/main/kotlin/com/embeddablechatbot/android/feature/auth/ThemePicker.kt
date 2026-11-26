package com.embeddablechatbot.android.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.embeddablechatbot.android.core.designsystem.LocalDarkTheme
import com.embeddablechatbot.android.core.designsystem.colorSchemeFor
import com.embeddablechatbot.android.core.designsystem.deviceColorsAvailable
import com.embeddablechatbot.android.core.domain.model.ThemeChoice
import com.embeddablechatbot.android.core.domain.model.ThemeMode
import com.embeddablechatbot.android.core.domain.model.ThemePalette

/** Color theme for the signed-in account. Each account on the phone keeps its own choice. */
@Composable
fun ThemeSection(email: String, theme: ThemeChoice, onPalette: (ThemePalette) -> Unit, onMode: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.settings_theme_hint, email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val modes = listOf(ThemeMode.System to R.string.settings_mode_system, ThemeMode.Light to R.string.settings_mode_light, ThemeMode.Dark to R.string.settings_mode_dark)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(selected = theme.mode == mode, onClick = { onMode(mode) }, shape = SegmentedButtonDefaults.itemShape(index, modes.size)) { Text(stringResource(label)) }
            }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemePalette.entries.filter { it != ThemePalette.Device || deviceColorsAvailable }.forEach { palette ->
                PaletteRow(palette, selected = theme.palette == palette, onClick = { onPalette(palette) })
            }
        }
    }
}

@Composable
private fun PaletteRow(palette: ThemePalette, selected: Boolean, onClick: () -> Unit) {
    // Swatches show the palette as it would look in the mode currently on screen.
    val scheme = colorSchemeFor(palette, LocalDarkTheme.current)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(palette.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            listOf(scheme.primary, scheme.secondaryContainer, scheme.tertiary).forEach { color ->
                Box(Modifier.size(26.dp).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(color, CircleShape))
            }
        }
    }
}
