package com.embeddablechatbot.android

import android.content.Context
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps background-only API tests running when the display sleeps; never unlocks the device. */
class DeviceVerificationWakeLock : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MailAssistant:DeviceVerification")
            lock.acquire(300_000L)
            try { base.evaluate() } finally { if(lock.isHeld)lock.release() }
        }
    }
}
