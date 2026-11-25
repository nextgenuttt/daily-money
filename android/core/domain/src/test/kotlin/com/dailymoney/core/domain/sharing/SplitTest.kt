package com.dailymoney.core.domain.sharing

import com.dailymoney.core.domain.money.TransactionType
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {

    /** docs/split-test-vectors.json is shared with the backend, so the preview equals what the server stores. */
    @Test
    fun `shared test vectors`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/split-test-vectors.json") }.first { it.exists() }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 10)
        cases.forEach { element ->
            val c = element.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val entries = c["entries"]!!.jsonArray.map { SplitEntry(it.jsonArray[0].jsonPrimitive.content, BigDecimal(it.jsonArray[1].jsonPrimitive.content)) }
            val result = SplitCalculator.split(c["total"]!!.jsonPrimitive.long, SplitMethod.valueOf(c["method"]!!.jsonPrimitive.content), entries)
            if (c.containsKey("error")) {
                assertTrue(name, result is SplitResult.Invalid)
            } else {
                assertEquals(name, SplitResult.Ok(c["shares"]!!.jsonArray.map { it.jsonPrimitive.long }), result)
            }
        }
    }

    private fun expense(creator: String, amount: Long, vararg shares: Pair<String, Long>, currency: String = "INR") = SharedExpense(
        id = "$creator$amount", groupId = "g", creatorId = creator, creatorName = creator, mine = creator == "me",
        type = TransactionType.EXPENSE, amountMinor = amount, currency = currency, categoryName = null, categoryIcon = null,
        occurredAt = Instant.EPOCH, note = null, paymentMethod = null, visibility = Visibility.ORGANIZATION,
        shares = shares.map { Share(it.first, it.second) },
    )

    private fun settlement(payer: String, receiver: String, amount: Long, status: SettlementStatus = SettlementStatus.CONFIRMED) = Settlement(
        "$payer$receiver$amount", "g", payer, receiver, amount, "INR", SettleMethod.UPI, status, null, payer, Instant.EPOCH,
    )

    @Test
    fun `balances net what I paid for others against what others paid for me`() {
        val balances = Balances.compute(
            "me",
            listOf(
                expense("me", 900, "me" to 300, "asha" to 300, "vinu" to 300),
                expense("asha", 600, "me" to 200, "asha" to 400),
                expense("vinu", 100),
                expense("asha", 50, "me" to 50, currency = "USD"),
            ),
            listOf(settlement("vinu", "me", 100), settlement("vinu", "me", 999, SettlementStatus.REQUESTED)),
            "INR",
        )
        assertEquals(listOf(PersonBalance("asha", 100), PersonBalance("vinu", 200)), balances.sortedBy { it.userId })
    }

    @Test
    fun `settled people drop off the list`() {
        val balances = Balances.compute("me", listOf(expense("asha", 400, "me" to 200, "asha" to 200)), listOf(settlement("me", "asha", 200)), "INR")
        assertTrue(balances.isEmpty())
    }
}
