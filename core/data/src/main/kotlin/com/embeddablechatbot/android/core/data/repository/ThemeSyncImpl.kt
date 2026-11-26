package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.network.ClientApi
import com.embeddablechatbot.android.core.data.network.PreferencesDto
import com.embeddablechatbot.android.core.data.network.ThemeDto
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.ThemeChoice
import com.embeddablechatbot.android.core.domain.model.ThemeMode
import com.embeddablechatbot.android.core.domain.model.ThemePalette
import com.embeddablechatbot.android.core.domain.model.map
import com.embeddablechatbot.android.core.domain.repository.ThemeSync
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSyncImpl @Inject constructor(private val api: ClientApi) : ThemeSync {
    override suspend fun pull(): Outcome<ThemeChoice?> = api.preferences().map { r ->
        r.value.theme?.let { t ->
            val palette = ThemePalette.entries.firstOrNull { it.name == t.palette } ?: return@let null
            val mode = ThemeMode.entries.firstOrNull { it.name == t.mode } ?: return@let null
            ThemeChoice(palette, mode)
        }
    }
    override suspend fun push(choice: ThemeChoice): Outcome<Unit> =
        api.savePreferences(PreferencesDto(ThemeDto(choice.palette.name, choice.mode.name))).map { }
}
