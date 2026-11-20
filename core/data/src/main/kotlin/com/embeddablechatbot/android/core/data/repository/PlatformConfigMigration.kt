package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import java.net.URI

/** Upgrade an explicitly opted-in local test build without changing its account identity. */
internal fun migrateLocalPlatform(stored: PlatformConfig, defaults: PlatformConfig, enabled: Boolean): PlatformConfig {
    if (!enabled || stored.supabaseUrl.trim().trimEnd('/') != defaults.supabaseUrl.trim().trimEnd('/') ||
        stored.supabasePublishableKey.trim() != defaults.supabasePublishableKey.trim() ||
        defaults.supabaseUrl.isBlank() || defaults.supabasePublishableKey.isBlank()) return stored

    val local = runCatching { URI(stored.platformUrl.trim()) }.getOrNull() ?: return stored
    val live = runCatching { URI(defaults.platformUrl.trim()) }.getOrNull() ?: return stored
    val loopbacks = setOf("localhost", "127.0.0.1", "10.0.2.2")
    fun URI.isBaseUrl() = userInfo == null && query == null && fragment == null && (path.isNullOrEmpty() || path == "/")
    if (local.scheme != "http" || local.host?.lowercase() !in loopbacks || local.port !in setOf(3000, 3100) || !local.isBaseUrl()) return stored
    if (live.scheme != "https" || live.host.isNullOrBlank() || live.host.lowercase() in loopbacks || !live.isBaseUrl()) return stored
    return stored.copy(platformUrl = defaults.platformUrl.trim().trimEnd('/'))
}
