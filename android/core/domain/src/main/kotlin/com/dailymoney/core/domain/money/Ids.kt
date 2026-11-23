package com.dailymoney.core.domain.money

import java.security.SecureRandom
import java.util.UUID

/** Client-generated time-ordered UUIDv7, so rows created offline keep their id when synced (LLD §1). */
object Ids {
    private val random = SecureRandom()

    fun newId(nowMillis: Long = System.currentTimeMillis()): String {
        val msb = (nowMillis shl 16) or 0x7000L or (random.nextInt().toLong() and 0x0FFFL)
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }
}
