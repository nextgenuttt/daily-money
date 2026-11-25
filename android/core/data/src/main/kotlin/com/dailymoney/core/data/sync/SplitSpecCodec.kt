package com.dailymoney.core.data.sync

import com.dailymoney.core.domain.sharing.SplitEntry
import com.dailymoney.core.domain.sharing.SplitMethod
import com.dailymoney.core.domain.sharing.SplitSpec
import java.math.BigDecimal

/** `METHOD;userId=value,userId=value`: ids are UUIDs and values plain decimals, so no escaping is needed. */
internal object SplitSpecCodec {

    fun encode(spec: SplitSpec): String =
        spec.method.name + ";" + spec.entries.joinToString(",") { "${it.userId}=${it.value.toPlainString()}" }

    fun decode(text: String?): SplitSpec? {
        if (text.isNullOrBlank()) return null
        val method = runCatching { SplitMethod.valueOf(text.substringBefore(';')) }.getOrNull() ?: return null
        val entries = text.substringAfter(';', "").split(',').filter { it.contains('=') }.mapNotNull {
            val value = it.substringAfter('=').toBigDecimalOrNull() ?: return@mapNotNull null
            SplitEntry(it.substringBefore('='), value)
        }
        return SplitSpec(method, entries)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
}
