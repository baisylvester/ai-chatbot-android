package com.embeddablechatbot.android.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** Emits whether the device currently has a validated network; used to refresh on reconnect. */
interface ConnectivityMonitor {
    val online: Flow<Boolean>
}
