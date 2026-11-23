package com.dailymoney.core.security

import com.google.crypto.tink.RegistryConfiguration
import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts files at rest (receipts) with AES-256-GCM in 4 KB segments, so large files stream without
 * loading into memory. The keyset is wrapped by a key in the Android Keystore. [name] is bound as
 * associated data, so a file can't be swapped for another one undetected.
 */
@Singleton
class FileCipher @Inject constructor(@ApplicationContext private val context: Context) {

    private val streamingAead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    fun encrypt(out: OutputStream, name: String): OutputStream = streamingAead.newEncryptingStream(out, name.toByteArray())

    fun decrypt(input: InputStream, name: String): InputStream = streamingAead.newDecryptingStream(input, name.toByteArray())

    private companion object {
        const val KEYSET_NAME = "dm_file_keyset"
        const val KEYSET_PREFS = "dm_file_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://dm_file_master_key"
    }
}
