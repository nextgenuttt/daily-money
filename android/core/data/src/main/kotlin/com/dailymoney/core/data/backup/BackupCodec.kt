package com.dailymoney.core.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Password-protected envelope for backup files: PBKDF2-HMAC-SHA256 (210k rounds, OWASP 2023 guidance)
 * derives an AES-256 key; AES-GCM encrypts and authenticates the payload, so a wrong password or a
 * modified file is always detected. Plain JVM crypto, so it is unit-tested without a device.
 */
class BackupCodec(private val iterations: Int = ITERATIONS) {

    @Serializable
    data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    class WrongPasswordException : Exception()
    class NotABackupException : Exception()

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun seal(plaintext: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val envelope = Envelope(FORMAT, VERSION, KDF, iterations, b64(salt), b64(iv), b64(cipher.doFinal(plaintext)))
        return json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    fun open(file: ByteArray, password: CharArray): ByteArray {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), file.toString(Charsets.UTF_8)) }.getOrNull()
        if (envelope == null || envelope.format != FORMAT || envelope.kdf != KDF) throw NotABackupException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(password, unb64(envelope.salt), envelope.iterations), GCMParameterSpec(128, unb64(envelope.iv)))
        cipher.updateAAD(AAD)
        return try {
            cipher.doFinal(unb64(envelope.data))
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun key(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)

    companion object {
        const val FORMAT = "dailymoney-backup"
        const val VERSION = 1
        const val KDF = "PBKDF2-HMAC-SHA256"
        const val ITERATIONS = 210_000
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "$FORMAT:$VERSION".toByteArray()
    }
}
