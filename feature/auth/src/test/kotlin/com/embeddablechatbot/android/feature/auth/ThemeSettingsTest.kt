package com.embeddablechatbot.android.feature.auth

import com.embeddablechatbot.android.core.domain.model.Account
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.ThemeChoice
import com.embeddablechatbot.android.core.domain.model.ThemeMode
import com.embeddablechatbot.android.core.domain.model.ThemePalette
import com.embeddablechatbot.android.core.domain.model.UserPreferences
import com.embeddablechatbot.android.core.domain.testing.FakeAuthRepository
import com.embeddablechatbot.android.core.domain.testing.FakeConfigRepository
import com.embeddablechatbot.android.core.domain.testing.FakePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each account keeps its own theme`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AuthState.SignedIn(Account("ana", "ana@example.test")))
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(auth, FakeConfigRepository(), prefs)
        vm.setPalette(ThemePalette.Forest); runCurrent()
        vm.setThemeMode(ThemeMode.Dark); runCurrent()

        auth.state.value = AuthState.SignedIn(Account("ben", "ben@example.test"))
        vm.setPalette(ThemePalette.Rose); runCurrent()

        val saved = prefs.preferences.first()
        assertEquals(ThemeChoice(ThemePalette.Forest, ThemeMode.Dark), saved.themeFor("ana"))
        assertEquals(ThemeChoice(ThemePalette.Rose, ThemeMode.System), saved.themeFor("ben"))
    }

    @Test
    fun `signed out changes are ignored and unknown accounts get the default`() = runTest(dispatcher) {
        val auth = FakeAuthRepository(AuthState.SignedOut())
        val prefs = FakePreferencesRepository()
        SettingsViewModel(auth, FakeConfigRepository(), prefs).setPalette(ThemePalette.Sunset); runCurrent()
        val saved = prefs.preferences.first()
        assertTrue(saved.accountThemes.isEmpty())
        assertEquals(ThemeChoice(), saved.themeFor(null))
        assertEquals(ThemeChoice(), UserPreferences().themeFor("someone"))
    }

    @Test
    fun `stored choices round trip and tolerate bad values`() {
        val choice = ThemeChoice(ThemePalette.Graphite, ThemeMode.Light)
        assertEquals(choice, ThemeChoice.decode(choice.encode()))
        assertEquals(ThemeChoice(), ThemeChoice.decode("nonsense"))
        assertEquals(ThemeChoice(ThemePalette.Violet), ThemeChoice.decode("violet:Unknown"))
    }
}
