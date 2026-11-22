package com.dailymoney.core.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted key-value store. Values are AES-256-GCM encrypted with a Tink keyset whose
 * master key lives in the Android Keystore, so a copied prefs file is useless off the device.
 * Reads are synchronous on purpose: callers are startup paths that need a value once.
 */
@Singleton
class SecurePrefs @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray()), Charsets.UTF_8)
        }.getOrNull()
    }

    fun putString(key: String, value: String) {
        val cipher = aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray())
        prefs.edit().putString(key, Base64.encodeToString(cipher, Base64.NO_WRAP)).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS_FILE = "dm_secure_prefs"
        const val KEYSET_PREFS_FILE = "dm_keyset_prefs"
        const val KEYSET_NAME = "dm_keyset"
        const val MASTER_KEY_URI = "android-keystore://dm_master_key"
    }
}
