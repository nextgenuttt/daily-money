package com.dailymoney.core.security

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileCipherTest {

    private val cipher = FileCipher(ApplicationProvider.getApplicationContext())
    private val receipt = ByteArray(50_000) { (it % 251).toByte() }

    private fun encrypt(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.encrypt(out, name).use { it.write(receipt) }
        return out.toByteArray()
    }

    @Test
    fun `round trip and ciphertext is not the plaintext`() {
        val encrypted = encrypt("a.webp")
        assertFalse(encrypted.copyOfRange(100, 200).contentEquals(receipt.copyOfRange(100, 200)))
        val decrypted = cipher.decrypt(ByteArrayInputStream(encrypted), "a.webp").use { it.readBytes() }
        assertArrayEquals(receipt, decrypted)
    }

    @Test(expected = IOException::class)
    fun `tampered file is rejected`() {
        val encrypted = encrypt("a.webp")
        encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2].toInt() xor 1).toByte()
        cipher.decrypt(ByteArrayInputStream(encrypted), "a.webp").use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun `a file can't be swapped in under another name`() {
        val encrypted = encrypt("a.webp")
        cipher.decrypt(ByteArrayInputStream(encrypted), "b.webp").use { it.readBytes() }
    }
}
