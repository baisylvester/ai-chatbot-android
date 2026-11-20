package com.embeddablechatbot.android.core.data.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small secrets store: values are sealed with an AES-256-GCM key wrapped by the Android Keystore. */
interface SecretStore {
    suspend fun read(name: String): String?
    suspend fun write(name: String, value: String?)
}

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_store")

@Singleton
class TinkSecretStore @Inject constructor(@param:ApplicationContext private val context: Context) : SecretStore {
    private val mutex = Mutex()
    private var aead: Aead? = null

    private fun aead(): Aead = aead ?: run {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            .also { aead = it }
    }

    override suspend fun read(name: String): String? = mutex.withLock {
        val sealed = context.secureDataStore.data.first()[stringPreferencesKey(name)] ?: return null
        try {
            String(aead().decrypt(Base64.decode(sealed, Base64.NO_WRAP), name.toByteArray()), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Keystore key lost (e.g. restored onto another device): the value can never be read again.
            context.secureDataStore.edit { it.remove(stringPreferencesKey(name)) }
            null
        } catch (_: IllegalArgumentException) {
            context.secureDataStore.edit { it.remove(stringPreferencesKey(name)) }
            null
        }
    }

    override suspend fun write(name: String, value: String?): Unit = mutex.withLock {
        val key = stringPreferencesKey(name)
        if (value == null) {
            context.secureDataStore.edit { it.remove(key) }
            Unit
        } else {
            val sealed = Base64.encodeToString(aead().encrypt(value.toByteArray(Charsets.UTF_8), name.toByteArray()), Base64.NO_WRAP)
            context.secureDataStore.edit { it[key] = sealed }
        }
    }

    private companion object {
        const val KEYSET_NAME = "session_keyset"
        const val KEYSET_PREFS = "session_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://embeddable_chatbot_session_master_key"
    }
}
