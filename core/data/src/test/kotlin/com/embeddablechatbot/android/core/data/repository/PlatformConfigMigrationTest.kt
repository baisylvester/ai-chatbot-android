package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformConfigMigrationTest {
    private val stored = PlatformConfig("http://127.0.0.1:3100", "https://auth.example", "public-key", "cb_personal")
    private val live = stored.copy(platformUrl = "https://mail.example")

    @Test fun `upgrades known local hosts while preserving account configuration`() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            val local = stored.copy(platformUrl = "http://$host:3100/")
            assertEquals(local.copy(platformUrl = live.platformUrl), migrateLocalPlatform(local, live, true))
        }
    }

    @Test fun `requires explicit build opt in`() {
        assertEquals(stored, migrateLocalPlatform(stored, live, false))
    }

    @Test fun `keeps custom and already live servers`() {
        for (url in listOf("https://custom.example", "http://192.168.1.50:3100", live.platformUrl)) {
            val custom = stored.copy(platformUrl = url)
            assertEquals(custom, migrateLocalPlatform(custom, live, true))
        }
    }

    @Test fun `keeps a different Supabase project`() {
        val other = stored.copy(supabaseUrl = "https://other-auth.example")
        assertEquals(other, migrateLocalPlatform(other, live, true))
    }

    @Test fun `keeps a different Supabase key`() {
        val other = stored.copy(supabasePublishableKey = "another-public-key")
        assertEquals(other, migrateLocalPlatform(other, live, true))
    }

    @Test fun `rejects insecure or malformed target configuration`() {
        for (url in listOf("http://mail.example", "https://localhost:3100", "https://user:password@mail.example", "not a URL")) {
            assertEquals(stored, migrateLocalPlatform(stored, live.copy(platformUrl = url), true))
        }
    }

    @Test fun `keeps loopback servers outside the known test addresses`() {
        for (url in listOf("http://127.0.0.1:9000", "http://127.0.0.1:3100/custom", "http://localhost:3100?tenant=other", "http://localhost:3100#custom")) {
            val custom = stored.copy(platformUrl = url)
            assertEquals(custom, migrateLocalPlatform(custom, live, true))
        }
    }

    @Test fun `normalizes trusted trailing slashes without replacing the assistant id`() {
        val current = stored.copy(supabaseUrl = "https://auth.example/")
        assertEquals(current.copy(platformUrl = live.platformUrl), migrateLocalPlatform(current, live.copy(platformUrl = live.platformUrl + "/"), true))
    }
}
