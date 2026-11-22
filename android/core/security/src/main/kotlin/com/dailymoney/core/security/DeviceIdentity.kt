package com.dailymoney.core.security

import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Install-level identifiers and the database passphrase. All wiped on sign-out. */
@Singleton
class DeviceIdentity @Inject constructor(private val securePrefs: SecurePrefs) {

    @Synchronized
    fun deviceId(): String = securePrefs.getString(KEY_DEVICE_ID)
        ?: UUID.randomUUID().toString().also { securePrefs.putString(KEY_DEVICE_ID, it) }

    /** 32 random bytes for SQLCipher, created on first use and stored encrypted. */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val existing = securePrefs.getString(KEY_DB_PASSPHRASE)
        if (existing != null) return existing.hexToBytes()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        securePrefs.putString(KEY_DB_PASSPHRASE, bytes.toHex())
        return bytes
    }

    fun clear() {
        securePrefs.clearAll()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DB_PASSPHRASE = "db_passphrase"
    }
}
