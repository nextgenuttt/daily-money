package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizerTest {

    private fun cat(id: String, kind: CategoryKind = CategoryKind.EXPENSE) = Category(id, id, kind, "others", 0, id.startsWith("sys_"))

    private val categories = listOf(
        cat("sys_food"), cat("sys_transport"), cat("sys_mobile"), cat("sys_internet"), cat("sys_shopping"), cat("office"),
        cat("sys_salary", CategoryKind.INCOME), cat("sys_refund", CategoryKind.INCOME),
    )

    @Test
    fun `merchant key ignores bank noise`() {
        assertEquals("swiggy", MerchantKey.of("UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment"))
        assertEquals("dominos pizza", MerchantKey.of("Dominos-Pizza #221"))
    }

    @Test
    fun `built in keywords pick a default category of the right kind`() {
        val c = Categorizer(emptyList(), emptyList(), categories)
        assertEquals("sys_food", c.suggest("UPI-SWIGGY-swiggy@ybl", TransactionType.EXPENSE)?.id)
        assertEquals("sys_internet", c.suggest("Airtel Xstream broadband", TransactionType.EXPENSE)?.id)
        assertEquals("sys_mobile", c.suggest("Airtel prepaid", TransactionType.EXPENSE)?.id)
        assertEquals("sys_refund", c.suggest("Amazon refund", TransactionType.INCOME)?.id)
        // "jiomart" is not "jio".
        assertNull(c.suggest("JIOMART order", TransactionType.EXPENSE)?.takeIf { it.id == "sys_mobile" })
        assertNull(c.suggest("Swiggy", TransactionType.TRANSFER))
    }

    @Test
    fun `my past choices beat built in keywords`() {
        val history = listOf(LabelledNote("UBER trip office", "office"), LabelledNote("Uber trip", "office"), LabelledNote("uber trip", "sys_transport"))
        assertEquals("office", Categorizer(emptyList(), history, categories).suggest("UBER TRIP 1234", TransactionType.EXPENSE)?.id)
    }

    @Test
    fun `my rules beat everything, longest first`() {
        val rules = listOf(CategoryRule("1", "amazon", "sys_shopping"), CategoryRule("2", "amazon prime", "office"))
        val history = listOf(LabelledNote("Amazon", "sys_food"))
        val c = Categorizer(rules, history, categories)
        assertEquals("office", c.suggest("AMAZON PRIME renewal", TransactionType.EXPENSE)?.id)
        assertEquals("sys_shopping", c.suggest("Amazon order", TransactionType.EXPENSE)?.id)
    }

    @Test
    fun `rules for deleted categories are ignored`() {
        val c = Categorizer(listOf(CategoryRule("1", "tea", "gone")), emptyList(), categories)
        assertEquals("sys_food", c.suggest("tea stall", TransactionType.EXPENSE)?.id)
    }
}
